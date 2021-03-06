package org.thunlp.language.chinese;

import java.io.File;
import java.io.IOException;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.thunlp.io.TextFileWriter;

public class ForwardMaxWordSegmentTest extends TestCase {
	File tmpWordlist;
	File tmpAutomata;

	public void setUp() throws IOException {
		String[] words = { "我们", "我", "是", "研究生", "研究", "科学", "数据", "数学", "数学", "的" };
		tmpWordlist = File.createTempFile("tmp-", "wordlist");
		tmpAutomata = File.createTempFile("tmp-", "automata");
		TextFileWriter w = new TextFileWriter(tmpWordlist.getAbsolutePath(), "UTF-8");
		for (String word : words) {
			System.out.println(word);
			w.writeLine(word);
		}
		w.close();

		ForwardMaxWordSegment.buildAutomata(tmpWordlist.getAbsolutePath(), tmpAutomata.getAbsolutePath());
	}

	public void tearDown() {
		tmpWordlist.delete();
		tmpAutomata.delete();
	}

	public void testSegment() throws IOException {
		String text = "我们是研究数学的研究生";
		String[] answer = { "我们", "是", "研究", "数学", "的", "研究生" };

		System.setProperty("wordsegment.automata.file", tmpAutomata.getAbsolutePath());
		ForwardMaxWordSegment ws = new ForwardMaxWordSegment();
		String[] result = ws.segment(text);
		Assert.assertNotNull(result);

		Assert.assertEquals(answer.length, result.length);
		for (int i = 0; i < result.length; i++) {
			System.out.println(result[i]);
			Assert.assertEquals(answer[i], result[i]);
		}
	}

	public void testLetterAndDigit() throws IOException {
		String text = "我们是1982研究生, yes!";
		String[] answer = { "我们", "是", "1982", "研究生", ",", "yes", "!" };

		System.setProperty("wordsegment.automata.file", tmpAutomata.getAbsolutePath());
		ForwardMaxWordSegment ws = new ForwardMaxWordSegment();
		String[] result = ws.segment(text);
		Assert.assertNotNull(result);

		Assert.assertEquals(answer.length, result.length);
		for (int i = 0; i < result.length; i++) {
			System.out.println(result[i]);
			Assert.assertEquals(answer[i], result[i]);
		}
	}
}
