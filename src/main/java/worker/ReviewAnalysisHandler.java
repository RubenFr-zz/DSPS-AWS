package worker;

import org.javatuples.Pair;
import org.json.simple.JSONArray;
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

    public LinkedList<String> work (String reviews) throws ParseException {
        LinkedList<String> report = new LinkedList<>();

        JSONParser parser = new JSONParser();
        Object jsonObj = parser.parse(reviews);
        JSONObject jsonObject = (JSONObject) jsonObj;
        JSONArray arr = (JSONArray) jsonObject.get("reviews");

        for (Object o : arr) {
            JSONObject review = (JSONObject) o;
            Pair<Integer, LinkedList<String>> res = processReview((String) review.get("text"));

            JSONObject out = new JSONObject();
            out.put("link", review.get("link"));
            out.put("rating", review.get("rating"));
            out.put("sentiment", res.getValue0() + 1);
            out.put("entities", res.getValue1().toString());

            report.add(out.toJSONString());
        }
        return report;
    }

    private Pair<Integer, LinkedList<String>> processReview(String review) {
        int sentiment = sentimentAnalysisHandler.findSentiment(review);
        LinkedList<String> entities =  namedEntityRecognitionHandler.findEntities(review);
        return new Pair<>(sentiment, entities);
    }

}
