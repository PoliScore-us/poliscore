package us.poliscore.interpretation;

public class InterpretationMissingReferencesException extends RuntimeException {

	private static final long serialVersionUID = -881813231368393431L;
	
	public InterpretationMissingReferencesException(String reason) {
		super(reason);
	}

}
