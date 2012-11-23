package ru.yandex.jenkins.plugins.compound;

import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.model.Computer;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * A {@link Launcher} for {@link CompoundSlave}.
 *
 * Tries to launch all sub-slaves first and marks them as non-accepting tasks when in {@link CompoundSlave}.
 *
 * @author pupssman
 *
 */
public class CompoundLauncher extends ComputerLauncher {
	private static final Logger logger = Logger.getLogger(CompoundLauncher.class.getCanonicalName());

	private CompoundSlave compoundSlave;

	public CompoundLauncher(CompoundSlave slave) {
		this.compoundSlave = slave;
	}

	private void say(TaskListener listener, String message) {
		String actualMessage = "[CompoundLauncher] " + message;
		getLogger().info(actualMessage);
		listener.getLogger().println(actualMessage);
	}

	@Override
	public void launch(SlaveComputer computer, final TaskListener listener) throws IOException, InterruptedException {
		List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>();

		for(final Slave slave: getSlaves()) {
			say(listener, "Launching sub-slave " + slave.getNodeName());
			SlaveComputer slaveComputer = slave.getComputer();

			if (slaveComputer.isConnecting() || slaveComputer.isOnline()) {
				say(listener, slave.getNodeName() + " already running");
				CompoundSlave.enslave(slave, compoundSlave);
				continue;
			}

			futures.add(Computer.threadPoolForRemoting.submit(new Callable<Boolean>() {
				@Override
				public Boolean call() throws Exception {
					slave.getLauncher().launch(slave.getComputer(), listener);
					CompoundSlave.enslave(slave, compoundSlave);
					return slave.getComputer().isOnline();
				}
			}));
		}

		boolean allSlavesLaunched = true;

		for (Future<Boolean> future: futures) {
			try {
				allSlavesLaunched &= future.get();
			} catch (ExecutionException e) {
				e.printStackTrace(listener.fatalError("Sub-slave start failed"));
				allSlavesLaunched = false;
			}
		}

		if (allSlavesLaunched) {
			say(listener, "Launching root");
			compoundSlave.getSelf().getComputer().getLauncher().launch(computer, listener);
		} else {
			say(listener, "Some slaves failed to come online, not launching root.");
		}
	}

	private Logger getLogger() {
		return Logger.getLogger(CompoundLauncher.class.getCanonicalName());
	}

	private Collection<Slave> getSlaves() {
		List<Slave> result = new ArrayList<Slave>();

		for (List<Slave> slaves: compoundSlave.getAllSlaves().values()) {
			result.addAll(slaves);
		}

		return result;
	}

	@Override
	public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
		for(Slave slave: getSlaves()) {
			CompoundSlave.free(slave);
			slave.getLauncher().afterDisconnect(slave.getComputer(), listener);
		}
	}

	@Override
	public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
		for(Slave slave: getSlaves()) {
			slave.getLauncher().beforeDisconnect(slave.getComputer(), listener);
		}
	}

}
