package com.arthadhruva.riskengine.notification;

/** What a notification is about; users choose delivery mode per type. DIGEST_SUMMARY is the
 * digest itself and is always delivered instantly (it cannot be digested again or switched off). */
public enum NotificationType { CASE_ASSIGNED, NOTE_ADDED, AUTOMATION, DIGEST_SUMMARY }
