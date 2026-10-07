package com.livingdocs.modules.auth.repository;

import com.livingdocs.modules.auth.model.OAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OAuthStateRepository extends JpaRepository<OAuthState, String> {
}
