package dev.townhall.city;

import java.util.List;

/** Optional town features. Definitions and memberships live in native SavedData, not in this config. */
public final class CitySettings {
	public boolean claimsEnabled = false;
	public boolean shopsEnabled = true;
	public boolean rolesInChat = true;
	public boolean rolesInTab = true;
	public String policeLocation = "gefaengnis";
	public int policeMaxMinutes = 15;
	public boolean auditEnabled = true;
	public int auditMaxEntries = 50_000;

	public void validate(List<String> errors) {
		if (policeLocation == null || policeLocation.isBlank()) errors.add("city.policeLocation is missing");
		if (policeMaxMinutes < 1 || policeMaxMinutes > 1440) errors.add("city.policeMaxMinutes must be 1-1440");
		if (auditMaxEntries < 100 || auditMaxEntries > 100_000) errors.add("city.auditMaxEntries must be 100-100000");
	}
}
