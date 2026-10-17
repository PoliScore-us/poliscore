package us.poliscore.referendum;
import java.time.LocalDate;
import java.util.List;
import us.poliscore.model.referendum.Referendum;
public interface ReferendumSourceFetcher {
    boolean supports(String namespace, LocalDate electionDate);
    List<Referendum> fetch(LocalDate electionDate) throws Exception;
}
