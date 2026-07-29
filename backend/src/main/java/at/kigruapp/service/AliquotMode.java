package at.kigruapp.service;

public enum AliquotMode {
    NONE, WHOLE_MONTH, PER_DAY;

    public static AliquotMode fromString(String s) {
        if (s == null) return NONE;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
