package com.pm.notificationservice.controller;

import com.pm.notificationservice.dto.ApiResponse;
import com.pm.notificationservice.dto.PushConfigResponse;
import com.pm.notificationservice.dto.PushSubscriptionRequest;
import com.pm.notificationservice.dto.PushUnsubscribeRequest;
import com.pm.notificationservice.push.PushProperties;
import com.pm.notificationservice.security.JwtUserPrincipal;
import com.pm.notificationservice.service.PushSubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a browser registers itself for web push. Thin by convention: the caller is resolved from
 * the JWT, never from the body.
 */
@RestController
@RequestMapping("/api/v1/push")
public class PushController {

    private final PushSubscriptionService pushSubscriptionService;
    private final PushProperties pushProperties;

    public PushController(PushSubscriptionService pushSubscriptionService,
                          PushProperties pushProperties) {
        this.pushSubscriptionService = pushSubscriptionService;
        this.pushProperties = pushProperties;
    }

    /**
     * The VAPID public key the browser needs before it can subscribe, plus whether the server can
     * actually push at all — the SPA hides its "enable notifications" control when it cannot, which
     * is better than letting a user grant permission that leads nowhere. The key is public by
     * design (it travels in the clear on every push), so exposing it costs nothing.
     */
    @GetMapping("/public-key")
    public ApiResponse<PushConfigResponse> publicKey() {
        return ApiResponse.of(new PushConfigResponse(
                pushProperties.isConfigured(), pushProperties.getPublicKey()));
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@AuthenticationPrincipal JwtUserPrincipal principal,
                          @Valid @RequestBody PushSubscriptionRequest request) {
        pushSubscriptionService.subscribe(principal.getUserId(), request);
    }

    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal JwtUserPrincipal principal,
                            @Valid @RequestBody PushUnsubscribeRequest request) {
        pushSubscriptionService.unsubscribe(principal.getUserId(), request.getEndpoint());
    }
}
