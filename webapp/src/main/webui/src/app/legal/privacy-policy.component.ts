import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-privacy-policy',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="ps-pp-page">
      <div class="ps-pp-container">
        <header class="ps-pp-header">
          <h1>PoliScore Privacy Policy</h1>
          <p class="ps-pp-meta">
            Last updated: {{ lastUpdated }}
          </p>
        </header>

        <section class="ps-pp-section">
          <h2>1. Overview</h2>
          <p>
            This Privacy Policy explains how PoliScore (“PoliScore”, “we”,
            “our”, or “us”) collects, uses, and shares information when you
            access or use our website, applications, and related services
            (collectively, the “Service”).
          </p>
          <p>
            By using PoliScore, you agree to the collection and use of
            information in accordance with this Privacy Policy and our Terms
            of Service. If you do not agree, you should not use the Service.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>2. Information We Collect</h2>

          <h3>2.1 Information you provide to us</h3>
          <p>We collect information that you provide directly, such as:</p>
          <ul>
            <li>
              <strong>Account information.</strong> Email address and basic
              profile details you provide when you create an account or log in.
            </li>
            <li>
              <strong>Subscription and billing information.</strong>
              Limited payment-related information (such as subscription status,
              plan type, and billing history). Payment card details are
              processed by our third-party payment processor and are not stored
              by PoliScore.
            </li>
            <li>
              <strong>Feedback and communications.</strong> Messages, feature
              requests, support inquiries, and other communications you send us.
            </li>
            <li>
              <strong>Content you submit.</strong> Bill texts or other material
              you choose to upload for analysis through the Service.
            </li>
          </ul>

          <h3>2.2 Information collected automatically</h3>
          <p>
            When you use PoliScore, we automatically collect certain technical
            information, including:
          </p>
          <ul>
            <li>
              <strong>Usage data.</strong> Feature usage, pages viewed,
              clicks, search queries, and other interactions with the Service.
            </li>
            <li>
              <strong>Device and log data.</strong> IP address, browser type,
              operating system, timestamps, and diagnostic logs.
            </li>
            <li>
              <strong>Cookies and similar technologies.</strong> Small data
              files stored on your device to keep you logged in, remember
              preferences, and help us understand how the Service is used.
            </li>
          </ul>

          <h3>2.3 Information from third parties</h3>
          <p>
            We may receive information about you from third parties, such as
            authentication providers, analytics tools, or payment processors,
            in order to operate and improve the Service.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>3. How We Use Your Information</h2>
          <p>We use the information we collect to:</p>
          <ul>
            <li>Provide, maintain, and improve PoliScore and its features;</li>
            <li>Authenticate users and secure access to the Service;</li>
            <li>
              Process subscriptions, payments, and manage account status;
            </li>
            <li>
              Generate and deliver PoliScore analytics, scores, summaries,
              references, and other results;
            </li>
            <li>
              Analyze Service usage to prioritize new features and improve
              reliability and performance;
            </li>
            <li>
              Communicate with you about updates, changes to the Service,
              and important account or security notices;
            </li>
            <li>
              Comply with legal obligations and enforce our Terms of Service.
            </li>
          </ul>
        </section>

        <section class="ps-pp-section">
          <h2>4. AI & Analytical Processing</h2>
          <p>
            PoliScore relies heavily on artificial intelligence and data
            processing. When you use the Service, we may process legislative
            data, uploaded bill texts, and interaction logs through AI models
            and analytical pipelines in order to generate scores, summaries,
            and other outputs.
          </p>
          <p>
            We may retain logs and intermediate outputs to monitor model
            quality, investigate issues, improve performance, and develop new
            features. Where possible, this data is aggregated or de-identified.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>5. Cookies & Tracking Technologies</h2>
          <p>
            We use cookies and similar technologies to keep you signed in,
            remember preferences, protect your account, and understand how
            users interact with the Service.
          </p>
          <p>
            You can configure your browser to reject cookies, but some
            features of PoliScore may not function properly if cookies are
            disabled.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>6. How We Share Information</h2>
          <p>
            We do not sell your personal information. We may share information
            in the following limited circumstances:
          </p>
          <ul>
            <li>
              <strong>Service providers.</strong> With trusted third-party
              vendors who perform services on our behalf, such as hosting,
              payment processing, analytics, email delivery, and customer
              support.
            </li>
            <li>
              <strong>Legal and safety.</strong> When required by law or when
              we believe in good faith that disclosure is reasonably necessary
              to protect the rights, property, or safety of PoliScore, our
              users, or the public.
            </li>
            <li>
              <strong>Business transfers.</strong> In connection with a merger,
              acquisition, reorganization, or sale of assets, subject to any
              applicable legal requirements.
            </li>
            <li>
              <strong>With your consent.</strong> When you direct us to share
              information or otherwise consent to a specific disclosure.
            </li>
          </ul>
        </section>

        <section class="ps-pp-section">
          <h2>7. Data Retention</h2>
          <p>
            We retain personal information for as long as reasonably necessary
            to operate the Service, fulfill the purposes described in this
            Policy, and comply with legal or accounting obligations. Retention
            periods may vary depending on the type of data and how it is used.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>8. Data Security</h2>
          <p>
            We use reasonable technical and organizational measures designed
            to protect your information from unauthorized access, loss, misuse,
            or alteration. However, no method of transmission or storage is
            completely secure, and we cannot guarantee absolute security.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>9. Your Choices & Rights</h2>
          <p>
            Depending on your location and applicable law, you may have certain
            rights regarding your personal information, such as the right to:
          </p>
          <ul>
            <li>Access or request a copy of the personal data we hold about you;</li>
            <li>Request correction of inaccurate or incomplete data;</li>
            <li>Request deletion of certain data, subject to legal obligations;</li>
            <li>
              Object to or restrict certain types of processing;
            </li>
            <li>
              Withdraw consent where processing is based on your consent.
            </li>
          </ul>
          <p>
            To exercise these rights, you can contact us using the details
            in the <strong>Contact Us</strong> section below. We may need to
            verify your identity before fulfilling certain requests.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>10. Children’s Privacy</h2>
          <p>
            PoliScore is not directed to children under 13, and we do not
            knowingly collect personal information from children under 13.
            If you believe a child has provided us with personal information,
            please contact us so that we can take appropriate action.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>11. International Users</h2>
          <p>
            PoliScore is operated from the United States. If you access the
            Service from outside the U.S., you understand that your information
            may be processed and stored in the U.S. or other countries, where
            data protection laws may differ from those in your jurisdiction.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>12. Changes to This Privacy Policy</h2>
          <p>
            We may update this Privacy Policy from time to time. When we make
            material changes, we will update the “Last updated” date above and
            may provide additional notice, such as by email or in-app
            messaging.
          </p>
          <p>
            Your continued use of PoliScore after any changes become effective
            means you accept the updated Privacy Policy.
          </p>
        </section>

        <section class="ps-pp-section">
          <h2>13. Contact Us</h2>
          <p>
            If you have questions about this Privacy Policy or our data
            practices, you can contact us at:
          </p>
          <p class="ps-pp-contact">
            <span>Email:</span>
            <a href="mailto:contact@poliscore.us">contact&#64;poliscore.us</a>
          </p>
        </section>
      </div>
    </div>
  `,
  styles: [`
    .ps-pp-page {
      padding: 2rem 1rem 3rem;
      display: flex;
      justify-content: center;
    }

    .ps-pp-container {
      max-width: 960px;
      width: 100%;
      background: #ffffff;
      border-radius: 16px;
      padding: 2rem 2.5rem;
      box-shadow: 0 16px 40px rgba(15, 23, 42, 0.1);
    }

    .ps-pp-header h1 {
      margin: 0 0 .25rem;
      font-size: 1.9rem;
      font-weight: 700;
    }

    .ps-pp-meta {
      margin: 0 0 1.5rem;
      color: #6b7280;
      font-size: .9rem;
    }

    .ps-pp-section {
      margin-bottom: 1.5rem;
    }

    .ps-pp-section h2 {
      font-size: 1.1rem;
      margin-bottom: .5rem;
      font-weight: 600;
    }

    .ps-pp-section h3 {
      font-size: 1rem;
      margin: .75rem 0 .25rem;
      font-weight: 600;
    }

    .ps-pp-section p {
      margin: .25rem 0;
      line-height: 1.55;
      color: #374151;
    }

    .ps-pp-section ul {
      margin: .5rem 0 .5rem 1.25rem;
      padding-left: 0;
      color: #374151;
    }

    .ps-pp-section li {
      margin-bottom: .3rem;
    }

    .ps-pp-contact span {
      font-weight: 600;
    }

    @media (max-width: 768px) {
      .ps-pp-container {
        padding: 1.5rem 1.25rem;
        border-radius: 0;
        box-shadow: none;
      }
    }
  `]
})
export class PrivacyPolicyComponent {
  lastUpdated = 'November 17, 2025'; // update as needed
}
