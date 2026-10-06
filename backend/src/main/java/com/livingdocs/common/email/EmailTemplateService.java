package com.livingdocs.common.email;

/**
 * Plain-text email templates used by the auth flows.
 *
 * <p>Centralised here so subject / body stay in sync and so future
 * internationalisation (e.g. Vietnamese, English) is a one-file change.
 */
public final class EmailTemplateService {

    private EmailTemplateService() {
    }

    /**
     * Email sent to a freshly registered user with their 6-digit code.
     */
    public static EmailMessage registrationVerification(String to, String displayName, String code) {
        String subject = "Mã xác thực tài khoản LivingDocs của bạn";
        String body = """
                Xin chào %s,

                Cảm ơn bạn đã đăng ký tài khoản LivingDocs.
                Mã xác thực email của bạn là:

                    %s

                Mã này có hiệu lực trong 15 phút. Nếu bạn không yêu cầu đăng ký,
                vui lòng bỏ qua email này.

                Trân trọng,
                LivingDocs Team
                """.formatted(displayName == null || displayName.isBlank() ? "bạn" : displayName, code);
        return new EmailMessage(to, subject, body);
    }

    /**
     * Email sent for "forgot password" — same channel, different copy.
     */
    public static EmailMessage passwordReset(String to, String displayName, String code) {
        String subject = "Đặt lại mật khẩu LivingDocs";
        String body = """
                Xin chào %s,

                Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản LivingDocs của bạn.
                Mã xác nhận là:

                    %s

                Mã có hiệu lực trong 30 phút. Nếu bạn không yêu cầu đặt lại mật khẩu,
                vui lòng bỏ qua email này — tài khoản của bạn vẫn an toàn.

                Trân trọng,
                LivingDocs Team
                """.formatted(displayName == null || displayName.isBlank() ? "bạn" : displayName, code);
        return new EmailMessage(to, subject, body);
    }

    /**
     * Welcome email sent right after a user signs up via OAuth (no
     * password, no verification code needed because the provider already
     * verified the email).
     */
    public static EmailMessage welcome(String to, String displayName) {
        String subject = "Chào mừng bạn đến với LivingDocs";
        String body = """
                Xin chào %s,

                Tài khoản LivingDocs của bạn đã được tạo thành công.
                Email này đã được xác thực tự động thông qua nhà cung cấp
                đăng nhập bạn vừa sử dụng.

                Bạn có thể đăng nhập lại bất cứ lúc nào bằng cùng nhà cung cấp
                đó, hoặc đặt mật khẩu từ trang Hồ sơ để có thêm lựa chọn.

                Trân trọng,
                LivingDocs Team
                """.formatted(displayName == null || displayName.isBlank() ? "bạn" : displayName);
        return new EmailMessage(to, subject, body);
    }
}
