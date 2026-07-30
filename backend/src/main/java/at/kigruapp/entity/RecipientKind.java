package at.kigruapp.entity;

/** What a {@link RecipientSelection} points at. Determines how it is resolved to parents. */
public enum RecipientKind {
    /** A group field instance. Resolved via the assigned children to their families' parents. */
    GROUP,
    /** A parent team or the board. Resolved directly from the parents' team assignments. */
    TEAM,
    /** A team role or a board role. Resolved directly from the parents' role assignments. */
    ROLE
}
