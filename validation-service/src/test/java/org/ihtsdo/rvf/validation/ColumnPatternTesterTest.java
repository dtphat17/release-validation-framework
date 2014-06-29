package org.ihtsdo.rvf.validation;

import org.ihtsdo.release.assertion.ResourceProviderFactoryImpl;
import org.ihtsdo.release.assertion.log.TestValidationLogImpl;
import org.ihtsdo.release.assertion.log.ValidationLog;
import org.ihtsdo.rvf.assertion._1_0.Column;
import org.ihtsdo.rvf.assertion._1_0.ColumnPatternConfiguration;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;

public class ColumnPatternTesterTest {

    private ColumnPatternTester tester;
    private ConfigurationFactory factory;
    private TestReport testReport;

    private static final Pattern SIMPLE_REFSET = Pattern.compile("x?der2_sRefset_SimpleMap.*\\.txt");
    private static final Pattern COMPLEX_REFSET = Pattern.compile("x?der2_iissscRefset_ComplexMap.*\\.txt");
    private static final Pattern EXTENDED_REFSET = Pattern.compile("x?der2_iisssccRefset_ExtendedMap.*\\.txt");

    @Before
    public void setup() {
        Map<String, String> configs = new HashMap<>();
        configs.put("/column-pattern-configuration.xml", "x?der2_sRefset_SimpleMap.*\\.txt");
        configs.put("/ext-column-pattern-configuration.xml", "x?der2_iisssccRefset_ExtendedMap.*\\.txt");
        configs.put("/complex-column-pattern-configuration.xml", "x?der2_iissscRefset_ComplexMap.*\\.txt");
        factory = new ConfigurationFactory(configs, new ResourceProviderFactoryImpl());
    }

    @Test
    public void testFileNotFound() throws Exception {
        ResourceManager resourceManager = new TestFileResourceProvider(new File(""));
        testReport = new TestReport(new CsvResultFormatter());
        tester = new ColumnPatternTester(new TestValidationLogImpl(ColumnPatternTester.class), factory, resourceManager, testReport);

        ColumnPatternConfiguration configuration = factory.getConfiguration(SIMPLE_REFSET);
        ColumnPatternConfiguration.File file = configuration.getFile().get(0);
        Column column = file.getColumn().get(0);
        column.setName("id");
        column.setSctid("");

        tester.runTests();

        assertEquals(1, testReport.getErrorCount());
        assertEquals(0, testReport.getNumSuccesses());
    }

    @Test
    public void testInvalidId() throws Exception {
        String filename = "/der2_sRefset_SimpleMapDelta_INT_20140131.txt";
        File f = new File(getClass().getResource(filename).toURI());

        ResourceManager resourceManager = new TestFileResourceProvider(f);
        testReport = new TestReport(new CsvResultFormatter());
        tester = new ColumnPatternTester(new TestValidationLogImpl(ColumnPatternTester.class), factory, resourceManager, testReport);
        ColumnPatternConfiguration configuration = factory.getConfiguration(SIMPLE_REFSET);
        ColumnPatternConfiguration.File file = configuration.getFile().get(0);
        Column column = file.getColumn().get(0);
        column.setName("id");
        column.setSctid("");
        tester.runTests();

        assertEquals(2, testReport.getErrorCount());
        assertEquals(161, testReport.getNumSuccesses());
    }

    class TestFileResourceProvider implements ResourceManager {

        public TestFileResourceProvider(File file) {
            this.file = file;
            fileNames.add(file.getName());
        }

        @Override
        public BufferedReader getReader(String name, Charset charset) throws IOException {
            return new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        }

        @Override
        public boolean isFile(String filename) {
            return file == null || !file.isDirectory();
        }

        @Override
        public String getFilePath() {
            return file.getAbsolutePath();
        }

        @Override
        public List<String> getFileNames() {
            return fileNames;
        }

        private List<String> fileNames = new ArrayList<>();
        private File file;
    }
}