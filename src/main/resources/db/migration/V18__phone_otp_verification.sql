ALTER TABLE app_users ADD COLUMN phone_verified_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE phone_otp_codes (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    purpose VARCHAR(20) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_phone_otp_codes_user_id_purpose ON phone_otp_codes(user_id, purpose);
CREATE INDEX ix_phone_otp_codes_expires_at ON phone_otp_codes(expires_at);
