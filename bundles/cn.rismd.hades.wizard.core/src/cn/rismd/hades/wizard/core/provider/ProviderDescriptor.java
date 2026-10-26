package cn.rismd.hades.wizard.core.provider;

import java.util.List;

public record ProviderDescriptor(int schemaVersion, int protocolVersion, String id, ProviderVersion version,
        String vlnv, DisplayMetadata display, BackendDescriptor backend, AddressingDescriptor addressing,
        List<PageDescriptor> pages, List<ReportDescriptor> reports) {
    public ProviderDescriptor {
        pages = pages == null ? null : List.copyOf(pages);
        reports = reports == null ? List.of() : List.copyOf(reports);
    }
}
