package com.resumeshaper.resume;
import org.springframework.data.jpa.domain.Specification;
import java.util.UUID;

public class ResumeJobSpec {

    public static Specification<ResumeJob> forUser(UUID userId) {
        return (root, query, cb) ->
                cb.equal(root.get("user").get("id"), userId);
    }

    public static Specification<ResumeJob> roleContains(String search) {
        if (search == null || search.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(
                        cb.lower(root.get("roleLabel")),
                        "%" + search.toLowerCase() + "%"
                );
    }

    public static Specification<ResumeJob> isStarred(Boolean starred) {
        if (starred == null) return null;
        return (root, query, cb) ->
                cb.equal(root.get("starred"), starred);
    }
}

