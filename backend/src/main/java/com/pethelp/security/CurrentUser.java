package com.pethelp.security;

import java.util.List;

public record CurrentUser(Long id, List<String> roles) {
}
