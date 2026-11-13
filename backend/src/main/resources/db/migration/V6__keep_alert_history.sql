-- Deleting an alert channel used to delete the record of every alert sent to it (ON DELETE CASCADE),
-- so an incident's timeline could claim "no alerts sent" for alerts that really went out.
-- Now the record stays: channel_id is cleared and the channel's name and type are copied first
-- (AlertChannelService.delete). Alerts still waiting to be sent are deleted there explicitly.
ALTER TABLE alert_outbox
    ADD COLUMN channel_name text,
    ADD COLUMN channel_type text CHECK (channel_type IN ('EMAIL', 'SLACK', 'DISCORD'));
ALTER TABLE alert_outbox ALTER COLUMN channel_id DROP NOT NULL;
ALTER TABLE alert_outbox DROP CONSTRAINT alert_outbox_channel_id_fkey;
ALTER TABLE alert_outbox ADD CONSTRAINT alert_outbox_channel_id_fkey
    FOREIGN KEY (channel_id) REFERENCES alert_channels (id) ON DELETE SET NULL;
