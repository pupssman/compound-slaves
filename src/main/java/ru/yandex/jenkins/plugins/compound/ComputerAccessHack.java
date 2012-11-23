package ru.yandex.jenkins.plugins.compound;

import hudson.AbortException;
import hudson.model.ResourceList;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue.Executable;
import hudson.model.Queue.Task;
import hudson.model.queue.SubTask;
import hudson.model.queue.CauseOfBlockage;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collection;

public class ComputerAccessHack {
	private final Computer computer;

	public ComputerAccessHack(Computer computer) {
		this.computer = computer;
	}

	public void setNumExecutors(int number) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
		Method method = getMethod(Computer.class, "setNumExecutors");

		method.invoke(computer, new Integer(number));
	}

	public void occupyExecutors(CompoundSlave master) throws IllegalArgumentException, IllegalAccessException, IOException {
		Field field = getField(Executor.class, "executable");

		for (Executor executor: computer.getExecutors()) {
			field.set(executor, getDummy(master));
		}
	}

	private Executable getDummy(final CompoundSlave master) throws IOException {
		final String name = MessageFormat.format("Part of <{0}>", master.getDisplayName());

		Task task = new Task() {

			@Override
			public String getDisplayName() {
				return name;
			}

			@Override
			public Label getAssignedLabel() {
				return null;
			}

			@Override
			public Node getLastBuiltOn() {
				return null;
			}

			@Override
			public long getEstimatedDuration() {
				return -1;
			}

			@Override
			public Executable createExecutable() throws IOException {
				final Task task = this;

				return new Executable() {

					@Override
					public void run() {
						// pass
					}

					@Override
					public SubTask getParent() {
						return task;
					}

					@Override
					public long getEstimatedDuration() {
						return getParent().getEstimatedDuration();
					}
				};
			}

			@Override
			public Task getOwnerTask() {
				return this;
			}

			@Override
			public Object getSameNodeConstraint() {
				return null;
			}

			@Override
			public ResourceList getResourceList() {
				return new ResourceList();
			}

			@Override
			public boolean isBuildBlocked() {
				return getCauseOfBlockage() != null;
			}

			@Override
			public String getWhyBlocked() {
				return "";
			}

			@Override
			public CauseOfBlockage getCauseOfBlockage() {
				return null;
			}

			@Override
			public String getName() {
				return name;
			}

			@Override
			public String getFullDisplayName() {
				return name;
			}

			@Override
			public void checkAbortPermission() {
				throw new IllegalArgumentException();
			}

			@Override
			public boolean hasAbortPermission() {
				return false;
			}

			@Override
			public String getUrl() {
				return "computer/" + master.getNodeName() + "/";
			}

			@Override
			public boolean isConcurrentBuild() {
				return false;
			}

			@Override
			public Collection<? extends SubTask> getSubTasks() {
				return Arrays.asList(this);
			}};

		return task.createExecutable();
	}

	public void freeExecutors() throws IllegalArgumentException, IllegalAccessException {
		Field field = getField(Executor.class, "executable");

		for (Executor executor: computer.getExecutors()) {
			field.set(executor, null);
			executor.interrupt();
		}
	}

	private <T> Method getMethod(Class<T> clazz, String name) throws IllegalArgumentException {
		Method method = null;

		for(Method m: clazz.getDeclaredMethods()) {
			if (m.getName().equals(name)) {
				method = m;
			}
		}

		if (method == null) {
			throw new IllegalArgumentException("Can't find method " + name);
		}

		method.setAccessible(true);

		return method;
	}

	private <T> Field getField(Class<T> clazz, String name) throws IllegalArgumentException {
		Field field = null;

		for(Field f: clazz.getDeclaredFields()) {
			if (f.getName().equals(name)) {
				field = f;
			}
		}

		if (field == null) {
			throw new IllegalArgumentException("Can't find field " + name);
		}

		field.setAccessible(true);

		return field;
	}
}
