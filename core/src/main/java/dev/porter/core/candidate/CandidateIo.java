package dev.porter.core.candidate;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import dev.porter.core.json.Json;
import java.nio.file.Path;
import java.util.List;

/** Candidate logs: {@code *.candidates.jsonl} (one candidate per line) or a single {@code *.candidate.json}. */
public final class CandidateIo {
    private CandidateIo() {
    }

    public static List<Candidate> read(Path file) {
        return ContractIo.readValidated(file, Contract.CANDIDATE).stream()
                .map(n -> Json.json().treeToValue(n, Candidate.class))
                .toList();
    }

    /** Validates and appends one candidate as a JSON line. */
    public static void append(Path jsonlFile, Candidate candidate) {
        ContractIo.appendJsonLine(jsonlFile, candidate, Contract.CANDIDATE);
    }
}
