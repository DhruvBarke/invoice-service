package com.sg.bootstrap.config;

import com.sg.domaininterface.port.out.AlertEmailPort;
import com.sg.domaininterface.model.alerting.EmailMessage;
import com.sg.domaininterface.port.out.RecordCodec;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import java.util.List;

/**
 * The adapters you must supply, as failing placeholders.
 *
 * <p><b>Why placeholders that throw rather than no-ops.</b> A missing wiring must fail immediately
 * and loudly at first use, not degrade silently into "the cache never returns anything" or "alerts
 * are never sent" — both of which look like working systems for days.
 *
 * <p>Replace each with a real {@code @Bean} in your application and delete the corresponding class
 * here. None of them is difficult; each is a translation layer, not logic.
 */
public final class AdapterPlaceholders {

    private AdapterPlaceholders() { }

    /**
     * Wraps your mail endpoint. Typically two lines:
     * <pre>
     * &#64;Bean AlertEmailPort alertEmailPort(YourMailApi api) {
     *     return msg -&gt; api.send(msg.to(), msg.subject(), msg.body());
     * }
     * </pre>
     */
    public static final class UnwiredAlertEmailPort implements AlertEmailPort {
        @Override
        public void send(EmailMessage message) {
            throw new IllegalStateException("No AlertEmailPort bean is wired");
        }
    }

    /**
     * Wraps your JSON library, so this project imposes none. With Jackson:
     * <pre>
     * &#64;Bean RecordCodec recordCodec(ObjectMapper mapper) {
     *     return new JacksonRecordCodec(mapper);
     * }
     * </pre>
     */
    public static final class UnwiredRecordCodec implements RecordCodec {
        @Override
        public String serialize(List<PartyRegistrationDetails> records) {
            throw new IllegalStateException("No RecordCodec bean is wired");
        }

        @Override
        public List<PartyRegistrationDetails> deserialize(String payload) {
            throw new IllegalStateException("No RecordCodec bean is wired");
        }
    }
}
