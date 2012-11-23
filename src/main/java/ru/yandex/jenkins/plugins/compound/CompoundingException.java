package ru.yandex.jenkins.plugins.compound;

/**
 * Thrown in case something goes wrong during slave compounding operations in {@link CompoundCloud}
 * @author pupssman
 *
 */
public class CompoundingException extends Exception {
	private static final long serialVersionUID = 1L;

	public CompoundingException(String message) {
		super(message);
	}

	public CompoundingException(String message, Throwable cause) {
		super(message, cause);
	}

}
