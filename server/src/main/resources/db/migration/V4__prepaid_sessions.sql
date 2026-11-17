-- Prepaid online sessions (docs/features/stripe-payments.md, decision P1). A booking request paid online when it is
-- made stays hidden from the trainer until the payment provider confirms the payment.
ALTER TABLE scheduling.session
    ADD COLUMN awaiting_payment boolean NOT NULL DEFAULT false;

-- A prepayment is returned when the request is not accepted, and settled against the fee when the client cancels
-- late or does not show up.
ALTER TABLE billing.refund DROP CONSTRAINT refund_reason_check;
ALTER TABLE billing.refund ADD CONSTRAINT refund_reason_check
    CHECK (reason IN ('TRAINER_CANCELLED', 'WITHDRAWAL', 'CORRECTION', 'NOT_ACCEPTED', 'CLIENT_CANCELLED', 'NO_SHOW'));

CREATE INDEX refund_pending ON billing.refund (created_at) WHERE status = 'PENDING';
