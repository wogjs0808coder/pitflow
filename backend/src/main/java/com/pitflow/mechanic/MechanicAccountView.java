package com.pitflow.mechanic;

import java.util.UUID;

public record MechanicAccountView(
    UUID id, UUID accountId, String code, String name, String email, boolean active) {}
