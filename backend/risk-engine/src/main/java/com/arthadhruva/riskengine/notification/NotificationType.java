package com.arthadhruva.riskengine.notification;

/** What a notification is about; users choose delivery mode per type. DIGEST_SUMMARY is the
 * digest itself and is always delivered instantly (it cannot be digested again or switched off).
 * DOCUMENT_ADDED goes to the borrowers linked to a loan when someone else attaches a document to it. */
public enum NotificationType { CASE_ASSIGNED, NOTE_ADDED, AUTOMATION, DIGEST_SUMMARY, DOCUMENT_ADDED }
