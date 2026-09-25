package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.CustomerDtos;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.service.CurrentUserService;
import com.byonix.shoplink.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerProfileControllerTest {
    @Mock CurrentUserService currentUser;
    @Mock UserRepository userRepository;
    @Mock OrderService orderService;
    @InjectMocks CustomerController controller;

    @Test
    void profileReturnsOnlySafeCurrentCustomerFields() {
        User user = customer("Old Name");
        when(currentUser.user()).thenReturn(user);

        CustomerDtos.ProfileResponse response = controller.profile().data();

        assertEquals(user.getId(), response.id());
        assertEquals("Old Name", response.fullName());
        assertEquals(user.getEmail(), response.email());
        assertEquals(user.getPhone(), response.phone());
    }

    @Test
    void updateChangesOnlyFullNameOnCurrentCustomer() {
        User user = customer("Old Name");
        when(currentUser.user()).thenReturn(user);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerDtos.ProfileResponse response = controller.updateProfile(new CustomerDtos.ProfileUpdateRequest("  New Name  ")).data();

        assertEquals("New Name", response.fullName());
        assertEquals("Old Name@example.com", response.email());
        assertEquals(Role.CUSTOMER, user.getRole());
        verify(userRepository).save(user);
    }

    @Test
    void nonCustomerPrincipalCannotUseProfileController() {
        User user = customer("Merchant");
        user.setRole(Role.MERCHANT_OWNER);
        when(currentUser.user()).thenReturn(user);

        assertThrows(org.springframework.security.access.AccessDeniedException.class, controller::profile);
        verifyNoInteractions(userRepository);
    }

    private User customer(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        user.setEmail("Old Name@example.com");
        user.setPhone("+962790000000");
        user.setRole(Role.CUSTOMER);
        return user;
    }
}
