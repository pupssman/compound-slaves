package ru.yandex.jenkins.plugins.compound;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.AbstractCloudImpl;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import jedi.functional.Filter;
import jedi.functional.FunctionalPrimitives;
import jedi.functional.Functor;
import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import ru.yandex.jenkins.plugins.compound.CompoundCloud.ConfigurationEntry.SlaveEntry;
import ru.yandex.jenkins.plugins.compound.CompoundSlave.DescriptorImpl;
import ru.yandex.jenkins.plugins.compound.CompoundSlave.Entry;


/**
 * Cloud, capable of deploying a {@link CompoundSlave} via other clouds
 *
 * @author pupssman
 */
public class CompoundCloud extends AbstractCloudImpl {
	private final String backend;
	private final int retryTimeout;
	private final List<ConfigurationEntry> configuration;
	private final AtomicInteger nodesProvisioned = new AtomicInteger(0);

	private static final Logger logger = Logger.getLogger(CompoundCloud.class.getCanonicalName());

	/**
	 * Describes a single deployeable configuration, i.e. a kind of {@link CompoundSlave} with given set of sub-nodes
	 *
	 * @author pupssman
	 */
	public static class ConfigurationEntry {
		private final LabelAtom labelAtom;
		private final List<SlaveEntry> entries;

		// when happened last deployment problems with this config
		long lastProblems = 0;

		/**
		 * Describes a single sub-node kind within a {@link CompoundSlave}
		 *
		 * @author pupssman
		 */
		public static class SlaveEntry {
			private final String role;
			private final LabelAtom labelAtom;
			private final int number;


			/**
			 * @param role within {@link CompoundSlave}
			 * @param labelAtom will be used to deploy sub-slave in {@link CompoundCloud#backendCloud}
			 * @param number of the copies
			 */
			@DataBoundConstructor
			public SlaveEntry(String role, String labelAtom, String number) {
				this.role = role;
				this.labelAtom = new LabelAtom(labelAtom);
				this.number = Integer.parseInt(number);
			}

			public String getRole() {
				return role;
			}

			public LabelAtom getLabelAtom() {
				return labelAtom;
			}

			public int getNumber() {
				return number;
			}
		}

		@DataBoundConstructor
		public ConfigurationEntry(String labelAtom, List<SlaveEntry> entries) {
			this.entries = entries;
			this.labelAtom = new LabelAtom(labelAtom);
		}

		public LabelAtom getLabelAtom() {
			return labelAtom;
		}

		public List<SlaveEntry> getEntries() {
			return entries;
		}
	}

	@DataBoundConstructor
	public CompoundCloud(String name, String maxInstances, String backendCloud, String retryTimeout, List<ConfigurationEntry> configuration) {
		super(name, maxInstances);
		this.backend = backendCloud;
		this.configuration = configuration;
		this.retryTimeout = Integer.parseInt(retryTimeout);
	}

	@Override
	public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
		List<PlannedNode> result = new ArrayList<NodeProvisioner.PlannedNode>();

		final ConfigurationEntry entry = FunctionalPrimitives.firstOrDefault(configuration, new Filter<ConfigurationEntry>() {
			@Override
			public Boolean execute(ConfigurationEntry value) {
				return label.matches(Arrays.asList(value.getLabelAtom()));
			}}, null);

		if (entry == null) {
			logger.warning(MessageFormat.format("Failed to deploy label {0} because no configuration found.", label));
			return result;
		}

		if (configHasRecentErrors(entry)) {
			logger.warning(MessageFormat.format("Requested to deploy label {0}, but corresponding config had problems recently. Will wait until timeout of {1} seconds to retry.", label.toString(), retryTimeout));
			return result;
		}

		final int nodeNumber = nodesProvisioned.incrementAndGet();

		Future<Node> future = Computer.threadPoolForRemoting.submit(new Callable<Node>() {
			@Override
			public Node call() throws Exception {
				try {
					return doCreateSlave(entry, nodeNumber);
				} catch (Exception e) {
					configProvisioningFailed(entry);
					throw e;
				}
			}
		});

		// we always set numExecutors to 1 since CompoundSlave's are single-use by design
		result.add(new PlannedNode("New-compound-node-" + nodeNumber, future, 1));

		return result;
	}

	protected CompoundSlave doCreateSlave(ConfigurationEntry entry, int nodeNumber) throws CompoundingException {
		List<Entry> slaveEntries = new ArrayList<CompoundSlave.Entry>();
		try {
			List<Future<Collection<Entry>>> newSlaves = new ArrayList<Future<Collection<Entry>>>();

			for (final SlaveEntry slaveEntry: entry.getEntries()) {
				newSlaves.add(doProvisionSubSlave(slaveEntry));
			}

			// cleanup flag. We can't do cleanup in catch because we need all futures to happen before cleanup
			boolean cleanup = false;

			for(Future<Collection<Entry>> future: newSlaves) {
				try {
					slaveEntries.addAll(future.get());
				} catch (InterruptedException e) {
					logger.log(Level.SEVERE, "InterruptedException: " + e.getMessage(), e);
					cleanup = true;
				} catch (ExecutionException e) {
					logger.log(Level.SEVERE, "ExecutionException: " + e.getMessage(), e);
					cleanup = true;
				}
			}

			if (cleanup) {
				logger.severe("Deployment failed, see log above. Cleaning up..");
				cleanup(slaveEntries);
				throw new CompoundingException("Deployment sub-slaves failed, see log");
			}

			return new CompoundSlave("Dynamic-compound-" + nodeNumber, "Dynamically-created compound node for label " + entry.getLabelAtom(), entry.getLabelAtom().toString(), slaveEntries);
		} catch (FormException e) {
			logger.log(Level.SEVERE, "Form exception: " + e.getMessage(), e);
			cleanup(slaveEntries);
			throw new CompoundingException("Configuration error: " + e.getMessage(), e);
		} catch (IOException e) {
			cleanup(slaveEntries);
			logger.log(Level.SEVERE, "IO exception: " + e.getMessage(), e);
			throw new CompoundingException("IO Exception: " + e.getMessage(), e);
		}
	}

	private Future<Collection<Entry>> doProvisionSubSlave(final SlaveEntry slaveEntry) {
		return Computer.threadPoolForRemoting.submit(new Callable<Collection<Entry>> () {
			@Override
			public Collection<Entry> call() throws Exception {
				final Jenkins jenkins = Jenkins.getInstance();
				List<PlannedNode> plannedNodes = new ArrayList<NodeProvisioner.PlannedNode>();

				for (int i = 0; i < slaveEntry.getNumber();i ++) {
					plannedNodes.addAll(getBackendCloud().provision(slaveEntry.getLabelAtom(), 1));
				}

				List<Entry> result = FunctionalPrimitives.map(plannedNodes, new Functor<PlannedNode, Entry>() {
					@Override
					public Entry execute(PlannedNode value) {
						try {
							jenkins.addNode(value.future.get());
							return new Entry(value.future.get().getNodeName(), slaveEntry.getRole());
						} catch (InterruptedException e) {
							logger.log(Level.SEVERE, "Interrupted", e);
							return null;
						} catch (ExecutionException e) {
							logger.log(Level.SEVERE, "Provisioning failed", e.getCause());
							return null;
						} catch (IOException e) {
							logger.log(Level.SEVERE, "Failed to add Node to jenkins: " + e.getMessage(), e.getCause());
							return null;
						}
					}
				});

				if (result.contains(null)) {
					logger.warning("Provisioning failed, cleaning up");
					cleanup(result);
					throw new CompoundingException("Some provisioning failed, see log above.");
				} else if (result.size() != slaveEntry.getNumber()) {
					logger.warning(MessageFormat.format("Provisioning failed to fullfill request, gave us {0} nodes instead of {1}", result.size(), slaveEntry.getNumber()));
					cleanup(result);
					throw new CompoundingException("Wrong nuber of nodes got provisioned, see log above.");
				} else {
					return result;
				}
			}
		});
	}

	/**
	 * Cleans up all the created stuff in these entries.
	 *
	 * Terminates {@link AbstractCloudSlave}s and removes all the others
	 * (in case {@link CompoundCloud#backendCloud} gives us regular slaves instead of {@link AbstractCloudSlave})
	 * @param entries
	 * @throws InterruptedException
	 * @throws IOException
	 */
	private void cleanup(Collection<Entry> entries) {
		Jenkins jenkins = Jenkins.getInstance();
		for (Entry entry: entries) {
			if (entry != null) {
				Node node = jenkins.getNode(entry.getName());
				if (node != null) {
					try {
						if (node instanceof AbstractCloudSlave) {
							logger.warning("Terminating CloudSlave " + node.getDisplayName());
							((AbstractCloudSlave) node).terminate();
						} else {
							logger.warning("Removing node " + node.getDisplayName());
							jenkins.removeNode(node);
						}
					} catch (IOException e) {
						logger.log(Level.WARNING, "Cleanup failed for " + node.getDisplayName(), e);
					} catch (InterruptedException e) {
						logger.log(Level.WARNING, "Cleanup failed for " + node.getDisplayName(), e);
					}
				}
			}
		}
	}

	@Override
	public boolean canProvision(Label label) {
		for (ConfigurationEntry entry: configuration) {
			if (label.matches(Arrays.asList(entry.getLabelAtom()))) {
				return true;
			}
		}
		return false;
	}

	private void configProvisioningFailed(ConfigurationEntry configurationEntry) {
		configurationEntry.lastProblems = System.currentTimeMillis();
	}

	private boolean configHasRecentErrors(ConfigurationEntry configurationEntry) {
		return (System.currentTimeMillis() - configurationEntry.lastProblems) < retryTimeout * 1000;
	}

	@Extension
	public static class CompoundCloudDescription extends Descriptor<Cloud> {
		@Override
		public String getDisplayName() {
			return "Compound cloud";
		}

		public ListBoxModel doFillRoleItems() {
			ListBoxModel model = new ListBoxModel();

			DescriptorImpl descriptor = (DescriptorImpl) Jenkins.getInstance().getDescriptor(CompoundSlave.class);

			for (String role: descriptor.getRoles()) {
				model.add(role, role);
			}

			return model;
		}

		public ListBoxModel doFillBackendCloudItems() {
			ListBoxModel model = new ListBoxModel();

			for (Cloud cloud: Jenkins.getInstance().clouds) {
				if (cloud instanceof CompoundCloud) {
					continue;
				}
				model.add(cloud.getDisplayName(), cloud.name);
			}

			return model;
		}

		public FormValidation doCheckRetryTimeout(@QueryParameter String retryTimeout) {
			if (retryTimeout.matches("\\d+")) {
				return FormValidation.ok();
			} else {
				return FormValidation.error("Bad value: use number");
			}
		}
	}

	public Cloud getBackendCloud() {
		return Jenkins.getInstance().getCloud(backend);
	}

	public String getBackend() {
		return backend;
	}

	public List<ConfigurationEntry> getConfiguration() {
		return configuration;
	}

	public int getRetryTimeout() {
		return retryTimeout;
	}

}
