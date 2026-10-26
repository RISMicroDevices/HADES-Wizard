package cn.rismd.hades.wizard.core.provider;

import java.util.regex.Pattern;

public record ProviderVersion(int major, int minor, int patch) implements Comparable<ProviderVersion> {
    private static final Pattern TEXT = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");

    public static ProviderVersion parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Provider version is required");
        }
        var matcher = TEXT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid provider version '" + value + "'; expected major.minor.patch");
        }
        return new ProviderVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    @Override
    public int compareTo(ProviderVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) {
            result = Integer.compare(minor, other.minor);
        }
        return result == 0 ? Integer.compare(patch, other.patch) : result;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
