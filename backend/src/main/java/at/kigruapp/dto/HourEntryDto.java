package at.kigruapp.dto;

/** Read-View eines Stunden-Eintrags. */
public class HourEntryDto {
    public String id;
    public String personId;
    public String semesterId;
    public String roleFieldInstanceId; // null = Kochen
    public String roleLabel;
    public String date;      // YYYY-MM-DD
    public int minutes;
    public String comment;
}
