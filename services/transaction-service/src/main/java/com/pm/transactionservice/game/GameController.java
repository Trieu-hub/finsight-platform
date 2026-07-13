package com.pm.transactionservice.game;

import com.pm.transactionservice.dto.ApiResponse;
import com.pm.transactionservice.game.GameDtos.GameStatus;
import com.pm.transactionservice.game.GameDtos.SpinRequest;
import com.pm.transactionservice.game.GameDtos.SpinResponse;
import com.pm.transactionservice.security.JwtUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LuckyMe game endpoints. {@code userId} comes ONLY from the JWT — a caller can only ever play
 * with, and be banned from, their own account.
 */
@RestController
@RequestMapping("/api/v1/game")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    /** The money and the lockout state, before a round. {@code walletId} defaults to the first. */
    @GetMapping("/roulette/status")
    public ApiResponse<GameStatus> status(@AuthenticationPrincipal JwtUserPrincipal principal,
                                          @RequestParam(required = false) Long walletId) {
        return ApiResponse.of(gameService.status(principal.getUserId(), walletId));
    }

    /** Plays one round: the server spins, settles, moves the money and may apply a ban. */
    @PostMapping("/roulette/spin")
    public ApiResponse<SpinResponse> spin(@AuthenticationPrincipal JwtUserPrincipal principal,
                                          @Valid @RequestBody SpinRequest request) {
        return ApiResponse.of(gameService.spin(principal.getUserId(), request));
    }
}
