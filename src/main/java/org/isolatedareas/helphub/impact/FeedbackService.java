package org.isolatedareas.helphub.impact;

import org.isolatedareas.helphub.domain.RequestStatus;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.isolatedareas.helphub.requests.SupplyRequestView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A resident's rating of a completed request, given once, from the feedback page or the assistant. */
@Service
public class FeedbackService {
    private final SupplyRequestRepository requests;
    private final JdbcClient jdbc;

    public FeedbackService(SupplyRequestRepository requests, JdbcClient jdbc) {
        this.requests = requests;
        this.jdbc = jdbc;
    }

    /** The resident's own completed request, ready to be rated; refuses one already rated. */
    public SupplyRequestView rateable(long requestId, long residentId) {
        SupplyRequestView request = requests.findOwned(requestId, residentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        if (request.status() != RequestStatus.FULFILLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Feedback requires a fulfilled request");
        }
        if (rated(requestId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Feedback already given");
        return request;
    }

    public boolean rated(long requestId) {
        return jdbc.sql("SELECT COUNT(*) FROM service_feedback WHERE request_id=:id").param("id", requestId)
            .query(Integer.class).single() > 0;
    }

    @Transactional
    public void record(long residentId, long requestId, int rating, String comments,
                       Integer previousTravelMinutes, Integer currentTravelMinutes) {
        rateable(requestId, residentId);
        jdbc.sql("""
                INSERT INTO service_feedback
                  (request_id, resident_id, rating, comments, previous_travel_minutes, current_travel_minutes)
                VALUES (:requestId, :residentId, :rating, :comments, :previous, :current)
                """).param("requestId", requestId).param("residentId", residentId)
            .param("rating", rating).param("comments", comments)
            .param("previous", previousTravelMinutes).param("current", currentTravelMinutes).update();
        jdbc.sql("""
                INSERT INTO impact_events (event_type, resident_id, request_id, value_number, metadata)
                VALUES ('SATISFACTION_RECORDED', :residentId, :requestId, :rating,
                  JSON_OBJECT('previousTravelMinutes', :previous, 'currentTravelMinutes', :current))
                """).param("residentId", residentId).param("requestId", requestId)
            .param("rating", rating).param("previous", previousTravelMinutes)
            .param("current", currentTravelMinutes).update();
    }
}
