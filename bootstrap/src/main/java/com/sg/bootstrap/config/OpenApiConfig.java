package com.sg.bootstrap.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The document-level half of the OpenAPI spec.
 *
 * <p>Operation and schema detail lives on {@code EInvoiceRegistrationApi} in rest-adapter, next to
 * the signatures it describes, so the two cannot drift. What is here is everything that is a
 * property of the deployment rather than of the contract: the title, the version, who to ask, and
 * which host the reader is pointed at.
 *
 * <p><b>This bean is not optional.</b> springdoc cannot set the title, description, contact or
 * licence from properties — without it the published spec is called "OpenAPI definition", is
 * version "v0", and says nothing about how to read a response.
 *
 * <p><b>Built in code rather than declared with {@code @OpenAPIDefinition}</b> so the version can
 * come from configuration. An annotation would bake it into the artifact and every environment
 * would serve a spec claiming to be the one it was built on.
 *
 * <p><b>No {@code servers} block, deliberately.</b> springdoc derives the server URL from the
 * incoming request, which follows whatever proxy or ingress the reader actually came through. A
 * configured one is only right in the environments that remember to override it, and wrong —
 * confidently, in a document people generate clients from — in the ones that do not.
 *
 * <p><b>No licence, and no contact unless one is configured.</b> There is no licence on this
 * service, so declaring one would be a claim nobody made. The contact is the same problem in a
 * smaller form: an invented address sends a reader who has a real question to a mailbox that
 * does not exist, which is worse for them than an empty field they can see is empty. Set
 * {@code invoice.service.api.contact-email} and it appears; leave it and the block is omitted.
 */
@Configuration
public class OpenApiConfig {

    private final String version;
    private final String contactName;
    private final String contactEmail;

    public OpenApiConfig(
            @Value("${invoice.service.api.version:1.0.0}") String version,
            @Value("${invoice.service.api.contact-name:}") String contactName,
            @Value("${invoice.service.api.contact-email:}") String contactEmail) {
        this.version = version;
        this.contactName = contactName;
        this.contactEmail = contactEmail;
    }

    /**
     * The contact block, or null when nothing was configured.
     *
     * <p>Null rather than an empty {@link Contact}: springdoc omits a null, and emits
     * {@code "contact": {}} for the empty one — a field that looks answered and is not.
     */
    private Contact contact() {
        boolean hasName = contactName != null && !contactName.isBlank();
        boolean hasEmail = contactEmail != null && !contactEmail.isBlank();
        if (!hasName && !hasEmail) {
            return null;
        }
        Contact contact = new Contact();
        if (hasName) {
            contact.setName(contactName.trim());
        }
        if (hasEmail) {
            contact.setEmail(contactEmail.trim());
        }
        return contact;
    }

    @Bean
    public OpenAPI invoiceServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Invoice Service — e-invoice registration")
                        .version(version)
                        .description("""
                                Registers inbound e-invoices as invoice payables.

                                ## Reading a response

                                Business outcomes are 200 with a verdict in the body, not HTTP \
                                errors. A refusal is a decision this service made about a \
                                document it understood; a 4xx means it could not read the \
                                request at all, and a 5xx means it failed to store one it could. \
                                Only the last is worth retrying.

                                ## What a registration does

                                One call writes across three tables — the payable envelope, its \
                                line items, and one row per document — correlated on the \
                                `invoiceReference` this service mints. Attachments go to the \
                                document store and only the returned handle is kept. Where the \
                                outcome carries a lifecycle event, the refusal or suspension \
                                reason is written with the row, so it survives even if the \
                                onward notification fails.

                                ## Scope

                                E-invoicing only. Manual capture and SGAi reach the same tables \
                                by a different path and are distinguished by `invoice_flow`.""")
                        .contact(contact()));
    }
}
