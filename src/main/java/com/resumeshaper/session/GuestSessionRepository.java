package com.resumeshaper.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GuestSessionRepository extends JpaRepository<GuestSession, String> {

    Optional<GuestSession> findByToken(String token);
}
