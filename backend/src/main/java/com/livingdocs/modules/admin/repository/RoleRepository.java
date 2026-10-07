package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByCode(String code);

    List<Role> findAllByOrderByDisplayOrderAscNameAsc();

    boolean existsByCode(String code);
}