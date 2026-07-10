package it.legislation.web;

public record NormattivaAutomationStatus(
        boolean enabled,
        String cron,
        String zone,
        String lastTriggeredAt,
        String lastState,
        String lastMessage
) {
}
