package com.resumeshaper.auth;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromEmail;

    @Value("${app.mail.app-name}")
    private String appName;

    public void sendOtp(String toEmail, String otp) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Your " + appName + " login code: " + otp);
            helper.setText(buildHtml(otp), true);
            mailSender.send(message);
            log.info("OTP email sent to {}", toEmail);
        } catch (MessagingException e) {
            log.error("Failed to send OTP to {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Failed to send OTP email. Please try again.");
        }
    }

    private String buildHtml(String otp) {
        return """
            <!DOCTYPE html>
            <html>
            <body style="margin:0;padding:0;background:#0f0f0f;font-family:'Segoe UI',sans-serif;">
              <div style="max-width:480px;margin:48px auto;background:#1a1a1a;border-radius:16px;
                          padding:48px 40px;border:1px solid #2a2a2a;">
                <div style="font-size:22px;font-weight:800;color:#a78bfa;margin-bottom:4px;">
                  ✦ ResumeShapers
                </div>
                <p style="color:#666;font-size:14px;margin-top:4px;margin-bottom:40px;">
                  Sign in / Sign up
                </p>
                <p style="color:#ccc;font-size:15px;margin-bottom:24px;">
                  Use this code to log in. It expires in <strong style="color:#fff;">5 minutes</strong>.
                </p>
                <div style="background:#252525;border-radius:12px;padding:28px;text-align:center;
                            margin-bottom:32px;border:1px solid #333;">
                  <span style="font-size:44px;font-weight:800;letter-spacing:14px;color:#fff;">
                    %s
                  </span>
                </div>
                <p style="color:#555;font-size:12px;text-align:center;margin:0;">
                  If you didn't request this, you can safely ignore this email.
                </p>
              </div>
            </body>
            </html>
            """.formatted(otp);
    }
}