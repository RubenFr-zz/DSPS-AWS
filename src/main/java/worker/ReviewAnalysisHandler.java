package worker;

import org.javatuples.Pair;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.LinkedList;

public class ReviewAnalysisHandler {

    private final SentimentAnalysisHandler sentimentAnalysisHandler;
    private final NamedEntityRecognitionHandler namedEntityRecognitionHandler;

    public ReviewAnalysisHandler() {
        sentimentAnalysisHandler = new SentimentAnalysisHandler();
        namedEntityRecognitionHandler = new NamedEntityRecognitionHandler();
    }

    public String work(String review) throws ParseException {
        JSONParser parser = new JSONParser();
        Object o = parser.parse(review);
        JSONObject obj = (JSONObject) o;
        Pair<Integer, LinkedList<String>> res = processReview((String) obj.get("text"));

        JSONObject report = new JSONObject();
        report.put("link", obj.get("link"));
        report.put("rating", obj.get("rating"));
        report.put("sentiment", res.getValue0() + 1);
        report.put("entities", res.getValue1().toString());

        return report.toJSONString();
    }

    private Pair<Integer, LinkedList<String>> processReview(String review) {
        int sentiment = sentimentAnalysisHandler.findSentiment(review);
        LinkedList<String> entities = namedEntityRecognitionHandler.findEntities(review);
        return new Pair<>(sentiment, entities);
    }

}
