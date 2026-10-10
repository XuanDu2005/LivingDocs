package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    List<Permission> findAllByOrderByCategoryAscDisplayOrderAscCodeAsc();

    List<Permission> findByCategoryOrderByDisplayOrderAscCodeAsc(String category);

    Optional<Permission> findByCode(String code);
}
