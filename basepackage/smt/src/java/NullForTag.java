import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import org.thunlp.misc.WeightString;

public class NullForTag {
	public static void main(String[] args) {
		try {
			HashMap<Integer, String> bookMap = new HashMap<Integer, String>();
			HashMap<String, Integer> idMap = new HashMap<String, Integer>();
			HashMap<Integer, String> bookTagMap = new HashMap<Integer, String>();

			HashMap<Integer, Double> proTable = new HashMap<Integer, Double>();
			HashMap<Integer, Double> inverseTable = new HashMap<Integer, Double>();

			// Read book.vcb
			String bookFile = "/home/cxx/result/smt/null/book.vcb";
			BufferedReader book = new BufferedReader(new InputStreamReader(new FileInputStream(bookFile), "UTF-8"));
			String bookLine;
			while ((bookLine = book.readLine()) != null) {
				String[] datas = bookLine.split(" ");
				bookMap.put(Integer.parseInt(datas[0]), datas[1]);
				idMap.put(datas[1], Integer.parseInt(datas[0]));
			}
			book.close();

			// Read bookTag.vcb
			String tagFile = "/home/cxx/result/smt/null/bookTag.vcb";
			BufferedReader bookTag = new BufferedReader(new InputStreamReader(new FileInputStream(tagFile), "UTF-8"));
			String tagLine;
			while ((tagLine = bookTag.readLine()) != null) {
				String[] datas = tagLine.split(" ");
				bookTagMap.put(Integer.parseInt(datas[0]), datas[1]);
			}
			bookTag.close();

			// read t1.5
			BufferedReader pro = new BufferedReader(new InputStreamReader(
					new FileInputStream("/home/cxx/result/smt/null/2010-12-03.153349.cxx.t1.5"), "UTF-8"));
			String proLine;
			while ((proLine = pro.readLine()) != null) {
				String[] data = proLine.split(" ");
				if (data.length != 3)
					continue;
				int first = Integer.parseInt(data[0]);
				int second = Integer.parseInt(data[1]);
				double probability = Double.parseDouble(data[2]);
				if (first == 0) {
					proTable.put(second, probability);
				}
				if (second == 0) {
					inverseTable.put(first, probability);
				}
			}
			pro.close();
			List<WeightString> tags = new ArrayList<WeightString>();
			for (Entry<Integer, Double> e : proTable.entrySet()) {
				tags.add(new WeightString(bookTagMap.get(e.getKey()), e.getValue()));
			}
			Collections.sort(tags, new Comparator<WeightString>() {
				@Override
				public int compare(WeightString o1, WeightString o2) {
					return Double.compare(o2.weight, o1.weight);
				}

			});

			BufferedWriter out = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream("/home/cxx/result/smt/null/nullToTag.txt"), "UTF-8"));
			out.write("nullToTag num:" + tags.size());
			out.newLine();
			out.flush();
			for (int i = 0; i < tags.size(); i++) {
				String tag = tags.get(i).text;
				double weight = tags.get(i).weight;
				out.write("null " + tag + " " + weight);
				out.newLine();
				out.flush();
			}
			out.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}
}
