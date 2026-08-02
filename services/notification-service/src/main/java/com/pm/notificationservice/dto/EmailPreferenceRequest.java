package com.pm.notificationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Only the switch. The address deliberately is not a field: accepting one would let a caller
 * point another account's alerts at a mailbox they control.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailPreferenceRequest {

    private boolean emailEnabled;
}
