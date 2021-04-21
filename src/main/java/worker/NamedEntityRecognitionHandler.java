package worker;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;

public class NamedEntityRecognitionHandler {

	private final StanfordCoreNLP NERPipeline;
	private final ArrayList<String> acceptedEntities;

	public NamedEntityRecognitionHandler() {
		acceptedEntities = new ArrayList<>(3);
		acceptedEntities.add("PERSON");
		acceptedEntities.add("LOCATION");
		acceptedEntities.add("ORGANIZATION");

		Properties props = new Properties();
		props.put("annotators", "tokenize , ssplit, pos, lemma, ner");
		props.setProperty("tokenize.options", "untokenizable=noneDelete");
		NERPipeline = new StanfordCoreNLP(props);
	}

	public LinkedList<String> findEntities(String review) {
		LinkedList<String> entities = new LinkedList<>();

		// create an empty Annotation just with the given text
		Annotation document = new Annotation(review);
		// run all Annotators on this text
		NERPipeline.annotate(document);
		// these are all the sentences in this document
		// a CoreMap is essentially a Map that uses class objects as keys and has values
		// with custom types
		List<CoreMap> sentences = document.get(SentencesAnnotation.class);
		for (CoreMap sentence : sentences) {
			// traversing the words in the current sentence
			// a CoreLabel is a CoreMap with additional token-specific methods
			for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
				// this is the text of the token
				String word = token.get(TextAnnotation.class);
				// this is the NER label of the token
				String ne = token.get(NamedEntityTagAnnotation.class);
				if (acceptedEntities.contains(ne))
					entities.add(word + ":" + ne);
			}
		}
		return entities;
	}
}
