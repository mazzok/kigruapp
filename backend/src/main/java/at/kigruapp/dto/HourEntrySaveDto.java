package at.kigruapp.dto;

/** Write-Payload. Das Rollen-Label wird serverseitig aus roleFieldInstanceId abgeleitet. */
public class HourEntrySaveDto {
    public String roleFieldInstanceId; // null = Kochen
    public String date;                // YYYY-MM-DD
    public int minutes;
    public String comment;
}
