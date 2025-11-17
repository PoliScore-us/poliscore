import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-terms-of-service',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="ps-tos-page">
      <div class="ps-tos-container">
        <header class="ps-tos-header">
          <h1>PoliScore Terms of Service</h1>
          <p class="ps-tos-meta">
            Last updated: {{ lastUpdated }}
          </p>
        </header>

        <section class="ps-tos-section">
          <h2>1. Overview</h2>
          <p>
            These Terms of Service (“Terms”) govern your access to and use of
            the PoliScore website, applications, and related services
            (collectively, “PoliScore”, “the Service”, “we”, “us”, or “our”).
            By creating an account, purchasing a subscription, or otherwise
            using PoliScore, you agree to be bound by these Terms.
          </p>
          <p>
            If you do not agree to these Terms, you may not use PoliScore.
            If you are using PoliScore on behalf of an organization, you
            represent that you have authority to bind that organization to
            these Terms.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>2. Description of the Service</h2>
          <p>
            PoliScore uses data sources, algorithms, and AI models to analyze
            legislative proposals and related information, and to generate
            scores, summaries, references, and other analytical content
            (“PoliScore Content”). PoliScore is designed as an informational
            and educational tool only.
          </p>
          <p>
            PoliScore does <strong>not</strong> provide legal, financial,
            or investment advice, and should not be relied upon as a sole
            source for decisions regarding voting, legislation, public policy,
            or political strategy. You are responsible for independently
            verifying information and for your own decisions and actions.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>3. Eligibility & Accounts</h2>
          <p>
            To use certain features of PoliScore, including paid
            subscriptions, you must create an account and provide accurate,
            current, and complete information. You are responsible for
            maintaining the confidentiality of your login credentials and for
            all activity that occurs under your account.
          </p>
          <p>
            You agree to promptly notify us of any unauthorized use of your
            account or any other breach of security.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>4. Subscriptions, Payments & Cancellations</h2>
          <p>
            PoliScore may offer free and paid plans. By subscribing to a paid
            plan, you authorize us (or our payment processor) to charge the
            applicable subscription fees on a recurring basis, using the
            payment method you provide.
          </p>
          <ul>
            <li>
              <strong>Billing cycle.</strong> Subscriptions are generally
              billed in advance on a recurring monthly basis, unless stated
              otherwise at checkout.
            </li>
            <li>
              <strong>Automatic renewal.</strong> Your subscription will
              automatically renew at the then-current price until canceled.
            </li>
            <li>
              <strong>Cancellation.</strong> You may cancel your subscription
              at any time via your account settings. Cancellations take effect
              at the end of the current billing period, and we generally do
              not provide refunds for partial periods unless required by law.
            </li>
            <li>
              <strong>Price changes.</strong> We may change subscription prices
              or plan features over time. In some cases we may offer “founding
              member” or “grandfathered” pricing; any such offers are subject
              to the conditions stated at the time of signup.
            </li>
          </ul>
        </section>

        <section class="ps-tos-section">
          <h2>5. AI-Generated Content & Limitations</h2>
          <p>
            Much of PoliScore’s output is generated or assisted by AI models.
            As a result, PoliScore Content may contain inaccuracies, omissions,
            or biases, and may change over time as models, prompts, and data
            sources evolve.
          </p>
          <ul>
            <li>
              PoliScore Content is provided on an “as is” and “as available”
              basis for informational purposes only.
            </li>
            <li>
              We do not guarantee that any score, summary, classification,
              reference, or other output is correct, complete, or up-to-date.
            </li>
            <li>
              You should not rely on PoliScore as a substitute for professional
              legal, policy, or research services.
            </li>
          </ul>
        </section>

        <section class="ps-tos-section">
          <h2>6. Acceptable Use</h2>
          <p>You agree that you will not:</p>
          <ul>
            <li>Use PoliScore for any unlawful purpose;</li>
            <li>
              Attempt to interfere with or compromise the integrity or security
              of the Service;
            </li>
            <li>
              Scrape, bulk download, or otherwise harvest PoliScore Content in
              a way that overloads or abuses the Service;
            </li>
            <li>
              Misrepresent PoliScore Content as if it were official statements
              from a government, legislator, or other third party;
            </li>
            <li>
              Use PoliScore to train or improve competing models or services
              without our prior written consent.
            </li>
          </ul>
        </section>

        <section class="ps-tos-section">
          <h2>7. Intellectual Property</h2>
          <p>
            PoliScore, including its logos, design, software, underlying
            models, and generated analytics, is protected by intellectual
            property laws. We grant you a limited, non-exclusive,
            non-transferable license to access and use the Service for your
            own personal or internal business purposes, subject to these Terms.
          </p>
          <p>
            Except as expressly allowed by these Terms or applicable law, you
            may not copy, modify, distribute, sell, lease, or create derivative
            works from PoliScore or PoliScore Content.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>8. User Content & Feedback</h2>
          <p>
            If you submit feedback, feature requests, or other content to us
            (“User Content”), you grant us a worldwide, perpetual, irrevocable,
            royalty-free license to use, reproduce, modify, and incorporate
            that content into PoliScore without any obligation to compensate
            you, subject to applicable privacy laws.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>9. Privacy</h2>
          <p>
            Our collection and use of personal data in connection with
            PoliScore is described in our Privacy Policy. By using PoliScore,
            you consent to our data practices as described there.
          </p>
          <p>
            Among other things, we may collect logs, feature usage, and
            feedback to improve the Service and to help prioritize new
            features.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>10. Disclaimers</h2>
          <p>
            PoliScore is provided on an “as is” and “as available” basis,
            without warranties of any kind, whether express or implied,
            including but not limited to implied warranties of merchantability,
            fitness for a particular purpose, non-infringement, and
            accuracy of information.
          </p>
          <p>
            We do not warrant that PoliScore will be uninterrupted, secure,
            or error-free, or that any defects will be corrected.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>11. Limitation of Liability</h2>
          <p>
            To the maximum extent permitted by law, in no event will
            PoliScore, its creators, or affiliates be liable for any indirect,
            incidental, special, consequential, or punitive damages, or any
            loss of profits, revenues, data, or goodwill, arising out of or in
            connection with your use of the Service.
          </p>
          <p>
            To the extent we are found liable to you, our total aggregate
            liability for any claims arising out of or relating to the Service
            or these Terms will be limited to the greater of (a) the amount you
            paid to us for the Service in the 3 months preceding the event
            giving rise to the claim, or (b) US $25, unless a different amount
            is required by applicable law.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>12. Suspension & Termination</h2>
          <p>
            We may suspend or terminate your access to PoliScore at any time,
            with or without notice, if we reasonably believe you have violated
            these Terms, pose a security risk, or if we discontinue the
            Service. You may stop using PoliScore at any time and may cancel
            any subscription through your account.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>13. Changes to the Service or Terms</h2>
          <p>
            We may update PoliScore or these Terms from time to time. If we
            make material changes to the Terms, we will provide notice by
            posting the updated Terms on the site and updating the “Last
            updated” date above, and may also notify you through other
            channels.
          </p>
          <p>
            Your continued use of PoliScore after changes take effect
            constitutes your acceptance of the updated Terms.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>14. Governing Law</h2>
          <p>
           These Terms are governed by the laws of the United States and, to
           the extent not preempted, the laws of the applicable state or
           jurisdiction where the Service is offered, without regard to its
           conflict of law principles. You are responsible for complying with
           all local laws in your own jurisdiction.
          </p>
        </section>

        <section class="ps-tos-section">
          <h2>15. Contact</h2>
          <p>
            If you have questions about these Terms or about PoliScore,
            you can contact us at:
          </p>
          <p class="ps-tos-contact">
            <span>Email:</span> <a href="mailto:contact@poliscore.us">contact&#64;poliscore.us</a>
          </p>
        </section>
      </div>
    </div>
  `,
  styles: [`
    .ps-tos-page {
      padding: 2rem 1rem 3rem;
      display: flex;
      justify-content: center;
    }

    .ps-tos-container {
      max-width: 960px;
      width: 100%;
      background: #ffffff;
      border-radius: 16px;
      padding: 2rem 2.5rem;
      box-shadow: 0 16px 40px rgba(15, 23, 42, 0.1);
    }

    .ps-tos-header h1 {
      margin: 0 0 .25rem;
      font-size: 1.9rem;
      font-weight: 700;
    }

    .ps-tos-meta {
      margin: 0 0 1.5rem;
      color: #6b7280;
      font-size: .9rem;
    }

    .ps-tos-section {
      margin-bottom: 1.5rem;
    }

    .ps-tos-section h2 {
      font-size: 1.1rem;
      margin-bottom: .5rem;
      font-weight: 600;
    }

    .ps-tos-section p {
      margin: .25rem 0;
      line-height: 1.55;
      color: #374151;
    }

    .ps-tos-section ul {
      margin: .5rem 0 .5rem 1.25rem;
      padding-left: 0;
      color: #374151;
    }

    .ps-tos-section li {
      margin-bottom: .3rem;
    }

    .ps-tos-contact span {
      font-weight: 600;
    }

    @media (max-width: 768px) {
      .ps-tos-container {
        padding: 1.5rem 1.25rem;
        border-radius: 0;
        box-shadow: none;
      }
    }
  `]
})
export class TermsOfServiceComponent {
  lastUpdated = 'November 17, 2025'; // update as needed
}
