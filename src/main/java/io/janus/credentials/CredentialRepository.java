package io.janus.credentials;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CredentialRepository extends JpaRepository<Credential, UUID> { }
