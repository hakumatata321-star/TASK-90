package com.example.ricms.security;

import com.example.ricms.domain.entity.Permission;
import com.example.ricms.domain.entity.Role;
import com.example.ricms.domain.entity.User;
import com.example.ricms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads role authorities (ROLE_<name>) AND fine-grained permission authorities
 * (PERM_<RESOURCE_TYPE>:<OPERATION>) at login time so that every subsequent
 * permission check is an O(n) in-memory scan – no extra DB round-trip.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        List<GrantedAuthority> authorities = new ArrayList<>();

        for (Role role : user.getRoles()) {
            // ROLE_ authority for Spring Security role checks
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));

            // PERM_<RT>:<OP> authorities for fine-grained permission checks (Q1)
            for (Permission perm : role.getPermissions()) {
                String permAuthority = "PERM_" + perm.getResourceType() + ":" + perm.getOperation();
                // avoid duplicates when multiple roles grant the same permission
                SimpleGrantedAuthority ga = new SimpleGrantedAuthority(permAuthority);
                if (!authorities.contains(ga)) {
                    authorities.add(ga);
                }
            }
        }

        return new RicmsPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), authorities);
    }
}
