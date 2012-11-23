package ru.yandex.jenkins.plugins.compound;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.ComputerSet;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy.Always;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jedi.functional.FunctionalPrimitives;
import jedi.functional.Functor;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * A compound slave, containing other sub-slaves.
 *
 * Intended for use with {@link CompoundBuilder} to run commands on separate slaves.
 *
 * @author pupssman
 *
 */
public class CompoundSlave extends AbstractCloudSlave {

	private final LinkedHashMap<String, List<Slave>> slaves = new LinkedHashMap<String, List<Slave>>();
	private Slave self;

	private static final long serialVersionUID = 1L;
	private static final String ROOT = "ROOT";
	private static final Logger logger = Logger.getLogger(CompoundSlave.class.getCanonicalName());

	/**
	 * Entry to pass around jelly-made ui
	 * @author pupssman
	 */
	public static final class Entry {
		private String slave;
		private String role;

		@DataBoundConstructor
		public Entry(String slave, String role) {
			this.slave = slave;
			this.role = role;
		}

		public String getName() {
			return slave;
		}
		public void setSlave(String slave) {
			this.slave = slave;
		}
		public String getRole() {
			return role;
		}
		public void setRole(String role) {
			this.role = role;
		}
	}

	/**
	 * This is a hack method to deduce remote FS root to relatively to the ROOT slave FS root
	 * before anything to set it in a final field - see {@link CompoundSlave#CompoundSlave(String, String, String, List)}
	 *
	 * @param slaveNames
	 * @return
	 * @throws FormException
	 */
	private static String inventRemoteFS(Map<String, List<String>> slaveNames) throws FormException {
		if (!slaveNames.containsKey(ROOT)) {
			throw new FormException("There should be " + ROOT + " slave defined", "slave");
		}

		String rootSlaveName = slaveNames.get(ROOT).get(0);

		Slave rootNode = (Slave) Jenkins.getInstance().getNode(rootSlaveName);

		if (rootNode == null) {
			throw new FormException(ROOT + " slave has name " + rootSlaveName + " but it can't be found in Jenkins", "slave");
		}

		return rootNode.getRemoteFS() + "/compound-root";
	}

	protected static Map<String, List<String>> makeNames(List<Entry> entries) {
		Map<String, List<String>> slaveNames = new HashMap<String, List<String>>();

		for (Entry entry: entries) {
			if (slaveNames.get(entry.role) == null) {
				slaveNames.put(entry.role, new ArrayList<String>());
			}

			slaveNames.get(entry.role).add(entry.slave);
		}

		return slaveNames;
	}

	@DataBoundConstructor
	public CompoundSlave(String name, String description, String label, List<Entry> slaveEntries) throws FormException, IOException {
		this(name, description, inventRemoteFS(makeNames(slaveEntries)), label);

		Map<String, List<String>> slaveNames = makeNames(slaveEntries);

		Jenkins jenkins = Jenkins.getInstance();

		self = (Slave) jenkins.getNode(slaveNames.get(ROOT).get(0));

		for(String slaveRole: slaveNames.keySet()) {
			for(String slaveName: slaveNames.get(slaveRole)) {

				Node node = jenkins.getNode(slaveName);

				if (node == null) {
					throw new FormException("No slave " + slaveName + " found", "slave");
				} else if (!(node instanceof Slave)) {
					throw new FormException("Slave " + slaveName + " is not a slave but just a node", "slave");
				}

				// conquer the slave computer so we don't conflict with anyone else
				enslave((Slave) node, this);
				((Slave) node).setRetentionStrategy(getRetentionStrategy());

				getSlaves(slaveRole).add((Slave) node);
			}
		}
	}

	private CompoundSlave(String name, String description, String remoteFS, String label) throws FormException, IOException {
		super(name, description, remoteFS, 1, Mode.EXCLUSIVE, label, null, new Always(), new ArrayList<NodeProperty<Slave>>());
		setLauncher(new CompoundLauncher(this));
		self = this;
	}

	public List<Slave> getSlaves(String role) {
		synchronized(slaves) {
			if (slaves.get(role) == null) {
				slaves.put(role, new ArrayList<Slave>());
			}

			return slaves.get(role);
		}
	}

	public List<Entry> getEntries() {
		List<Entry> result = new ArrayList<CompoundSlave.Entry>();

		for (final java.util.Map.Entry<String, List<Slave>>  mapEntry: slaves.entrySet()) {
			result.addAll(FunctionalPrimitives.map(mapEntry.getValue(), new Functor<Slave, Entry>() {
				@Override
				public Entry execute(Slave value) {
					return new Entry(value.getNodeName(), mapEntry.getKey());
				}
			}));
		}

		return result;
	}

	public int getSlaveNumber() {
		return slaves.size();
	}

	@Extension
	public static class DescriptorImpl extends SlaveDescriptor {

		public DescriptorImpl() {
			super();
			load();
		}

		private List<String> roles = new ArrayList<String>(Arrays.asList(ROOT));

		public List<String> getRoles() {
			return new ArrayList<String>(roles);
		}

		@Override
		public void handleNewNodePage(ComputerSet computerSet, String name, StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
			super.handleNewNodePage(computerSet, name, req, rsp);
		}

		@Override
		public String getDisplayName() {
			return "Multi-computer slave";
		}

		public ListBoxModel doFillSlaveItems() {
			ListBoxModel model = new ListBoxModel();

			for(Node node: Jenkins.getInstance().getNodes()) {
				if (node instanceof CompoundSlave) {
					continue;
				}

				model.add(node.getDisplayName(), node.getNodeName());
			}

			return model;
		}

		public ListBoxModel doFillRoleItems() {
			ListBoxModel model = new ListBoxModel();

			for (String role: getRoles()) {
				model.add(role, role);
			}

			return model;
		}

		private void addRoleFrom(JSONObject roleObject) {
			String role = roleObject.getString("role");
			System.out.println("Got new role " + role);
			roles.add(role);
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
			System.out.println("Got formData " + formData);
			Object rolesObject = formData.get("roles");

			List<String> oldRoles = getRoles();
			roles.clear();

			if (rolesObject instanceof JSONObject) {
				// means we get a set of values
				addRoleFrom((JSONObject) rolesObject);
			} else if (rolesObject instanceof JSONArray) {
				// means we get just a single value from the page
				System.out.println("Got roles " + rolesObject);
				for(int i = 0; i < ((JSONArray) rolesObject).size(); i++) {
					addRoleFrom(((JSONArray) rolesObject).getJSONObject(i));
				}
			} else if (rolesObject != null) {
				// something unexpected - restore old roles
				roles.addAll(oldRoles);
			}
			save();
			return super.configure(req,formData);
		}

		public FormValidation doCheckRole(@QueryParameter String role) {
			if (role.matches("\\w+")) {
				return FormValidation.ok();
			} else {
				return FormValidation.error("Bad role - use only one word in alphanumerics");
			}
		}
	}

	public Map<String, List<Slave>> getAllSlaves() {
		return new HashMap<String, List<Slave>>(slaves);
	}

	public Slave getSelf() {
		return self;
	}

	@Override
	public AbstractCloudComputer<CompoundSlave> createComputer() {
		return new AbstractCloudComputer<CompoundSlave>(this);
	}

	/**
	 * Terminates all the cloud-based sub-slaves within the compound slave
	 */
	@Override
	protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
		for (List<Slave> slaves: getAllSlaves().values()) {
			for (Slave slave: slaves) {
				free(slave);
				if (slave instanceof AbstractCloudSlave) {
					try {
						listener.getLogger().println("Terminating sub-slave " + slave.getDisplayName());
						((AbstractCloudSlave)slave).terminate();
					} catch (IOException e) {
						e.printStackTrace(listener.fatalError("Terminating slave {0} failed", slave.getDisplayName()));
					} catch (InterruptedException e) {
						e.printStackTrace(listener.fatalError("Terminating slave {0} failed", slave.getDisplayName()));
					}
				}
			}
		}
	}

	public static void free(Slave slave) {
		try {
			new ComputerAccessHack(slave.getComputer()).freeExecutors();
		} catch (IllegalArgumentException e) {
			logger.info("Failed to re-enable slave " + slave.getDisplayName() + " due to error:" + e.getMessage());
		} catch (IllegalAccessException e) {
			logger.info("Failed to re-enable slave " + slave.getDisplayName() + " due to error:" + e.getMessage());
		}
		slave.getComputer().setAcceptingTasks(true);
	}

	public static void enslave(Slave slave, CompoundSlave master) {
		slave.getComputer().setAcceptingTasks(false);
		try {
			new ComputerAccessHack(slave.getComputer()).occupyExecutors(master);
		} catch (IllegalArgumentException e) {
			logger.info("Failed to fully conquer sub-slave " + slave.getDisplayName() + " due to error:" + e.getMessage());
		} catch (IllegalAccessException e) {
			logger.info("Failed to fully conquer sub-slave " + slave.getDisplayName() + " due to error:" + e.getMessage());
		} catch (IOException e) {
			logger.info("Failed to fully conquer sub-slave " + slave.getDisplayName() + " due to error:" + e.getMessage());
		}
	}
}
