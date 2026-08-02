package at.kigruapp.entity;

/** Ausgang eines Erinnerungs-Versands für einen einzelnen Kochdienst. */
public enum CookingReminderStatus {
    SENT,
    FAILED,
    NO_RECIPIENTS,
    ACCOUNT_UNAVAILABLE
}
