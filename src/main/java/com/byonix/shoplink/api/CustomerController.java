package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.CustomerDtos;
import com.byonix.shoplink.api.dto.OrderDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.service.CurrentUserService;
import com.byonix.shoplink.service.OrderService;
import com.byonix.shoplink.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CustomerController {
    private final CurrentUserService currentUser;
    private final UserRepository userRepository;
    private final OrderService orderService;

    @GetMapping
    public ApiResponse<CustomerDtos.ProfileResponse> profile() {
        return ApiResponse.ok(profileResponse(customer()));
    }

    @PutMapping
    public ApiResponse<CustomerDtos.ProfileResponse> updateProfile(@Valid @RequestBody CustomerDtos.ProfileUpdateRequest request) {
        User user = customer();
        user.setFullName(request.fullName().trim());
        return ApiResponse.ok(profileResponse(userRepository.save(user)));
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderDtos.OrderResponse>> orders(@RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orderService.customerOrders(page, size));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<OrderDtos.OrderResponse> order(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.customerOrder(id));
    }

    private User customer() {
        User user = currentUser.user();
        if (user.getRole() != Role.CUSTOMER) {
            throw new org.springframework.security.access.AccessDeniedException("Customer access required");
        }
        return user;
    }

    private CustomerDtos.ProfileResponse profileResponse(User user) {
        return new CustomerDtos.ProfileResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone());
    }
}
