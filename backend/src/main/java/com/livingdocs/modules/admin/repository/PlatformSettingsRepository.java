package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.PlatformSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlatformSettingsRepository extends JpaRepository<PlatformSettings, Integer> {
    default PlatformSettings getSingleton() {
        return findById(PlatformSettings.SINGLETON_ID).orElseGet(() -> {
            PlatformSettings ps = new PlatformSettings();
            save(ps);
            return ps;
        });
    }
}
