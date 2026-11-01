package dev.dinesh.argus.archive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;

/** Reads archive files back without Hadoop, for assertions. */
public final class ParquetTestFiles {

  /** Hadoop-free reads: the default codec factory needs Hadoop's runtime. */
  static final ParquetReadOptions OPTIONS =
      ParquetReadOptions.builder(new PlainParquetConfiguration())
          .withCodecFactory(new ZstdCodecFactory(ZstdCodecFactory.DEFAULT_LEVEL))
          .build();

  private ParquetTestFiles() {}

  public static List<Group> rows(Path file) throws IOException {
    List<Group> rows = new ArrayList<>();
    try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file), OPTIONS)) {
      MessageType schema = reader.getFooter().getFileMetaData().getSchema();
      MessageColumnIO io = new ColumnIOFactory().getColumnIO(schema);
      for (PageReadStore group = reader.readNextRowGroup();
          group != null;
          group = reader.readNextRowGroup()) {
        RecordReader<Group> records = io.getRecordReader(group, new GroupRecordConverter(schema));
        for (long i = 0; i < group.getRowCount(); i++) {
          rows.add(records.read());
        }
      }
    }
    return rows;
  }
}
