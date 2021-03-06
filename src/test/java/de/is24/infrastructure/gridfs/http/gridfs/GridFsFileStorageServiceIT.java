package de.is24.infrastructure.gridfs.http.gridfs;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.gridfs.GridFSDBFile;
import com.mongodb.gridfs.GridFSFile;
import de.is24.infrastructure.gridfs.http.category.LocalExecutionOnly;
import de.is24.infrastructure.gridfs.http.mongo.IntegrationTestContext;
import de.is24.infrastructure.gridfs.http.storage.FileDescriptor;
import de.is24.infrastructure.gridfs.http.storage.FileStorageItem;
import org.apache.commons.lang.time.DateUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.util.Date;
import java.util.List;

import static com.mongodb.gridfs.GridFSUtil.mergeMetaData;
import static de.is24.infrastructure.gridfs.http.gridfs.StorageServiceIT.TESTING_ARCH;
import static de.is24.infrastructure.gridfs.http.mongo.DatabaseStructure.GRIDFS_FILES_COLLECTION;
import static de.is24.infrastructure.gridfs.http.mongo.DatabaseStructure.MARKED_AS_DELETED_KEY;
import static de.is24.infrastructure.gridfs.http.mongo.DatabaseStructure.REPO_KEY;
import static de.is24.infrastructure.gridfs.http.utils.RepositoryUtils.simpleInputStream;
import static de.is24.infrastructure.gridfs.http.utils.RepositoryUtils.uniqueRepoName;
import static org.apache.commons.lang.time.DateUtils.addDays;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.data.mongodb.core.query.Update.update;
import static org.springframework.data.mongodb.gridfs.GridFsCriteria.whereMetaData;

@Category(LocalExecutionOnly.class)
public class GridFsFileStorageServiceIT {

  @ClassRule
  public static IntegrationTestContext context = new IntegrationTestContext();

  @Test
  public void deleteFilesMarkedAsDeleted() throws Exception {
    final Date now = new Date();
    final String nothingToDeleteRepo = context.storageTestUtils().givenFullRepository();
    givenTowOfThreeFilesToBeDeleted(now);

    context.fileStorageService().removeFilesMarkedAsDeletedBefore(now);

    final List<GridFSDBFile> fileList = context.gridFsTemplate()
        .find(query(whereMetaData(MARKED_AS_DELETED_KEY).ne(null)));
    assertThat(fileList.size(), is(1));

    final List<GridFSDBFile> filesInNothingToDeleteRepo = context.gridFsTemplate()
        .find(query(whereMetaData(REPO_KEY).is(nothingToDeleteRepo)));
    assertThat(filesInNothingToDeleteRepo.size(), is(4));
  }

  @Test
  public void metaDataForDeletionIsSetOnlyOnce() throws Exception {
    final String repoName = context.storageTestUtils().givenFullRepository();
    final Date yesterday = DateUtils.addDays(new Date(), -1);
    final String file = "a_file_to_be_deleted";
    FileDescriptor descriptor = new FileDescriptor(repoName, TESTING_ARCH, file);
    givenFileToBeDeleted(descriptor, yesterday);

    context.fileStorageService().markForDeletionByPath(descriptor.getPath());

    final FileStorageItem storageItem = context.fileStorageService().findBy(descriptor);

    assertThat(storageItem.getDateOfMarkAsDeleted(), is(equalTo(yesterday)));
  }

  @Test
  public void getCorruptFiles() throws Exception {
    setFilenameToNull(context.storageTestUtils().givenFullRepository());
    setMetadataToNull(context.storageTestUtils().givenFullRepository());

    List<FileStorageItem> corruptFiles = context.fileStorageService().getCorruptFiles();

    assertThat(corruptFiles.size(), greaterThanOrEqualTo(2));
    assertAllFilesAreCorrupt(corruptFiles);
  }

  @Test
  public void deleteCorruptFiles() throws Exception {
    setFilenameToNull(context.storageTestUtils().givenFullRepository());
    setMetadataToNull(context.storageTestUtils().givenFullRepository());

    context.fileStorageService().deleteCorruptFiles();
    assertThat(context.fileStorageService().getCorruptFiles().size(), is(0));
  }

  @Test
  public void ensureIndex() throws Exception {
    new GridFsFileStorageService(context.gridFs(), context.gridFsTemplate(), context.mongoTemplate());
    new GridFsFileStorageService(context.gridFs(), context.gridFsTemplate(), context.mongoTemplate());
    List<DBObject> indexInfos = context.mongoTemplate().getCollection(GRIDFS_FILES_COLLECTION).getIndexInfo();
    assertThat(indexInfos.size(), is(6));
  }

  @Test
  public void overwriteEvenMultipleFiles() throws Exception {
    String filename = uniqueRepoName() + "/repodata/repomd.xml";
    context.gridFs().createFile(simpleInputStream(), filename).save();
    context.gridFs().createFile(simpleInputStream(), filename).save();
    context.gridFs().createFile(simpleInputStream(), filename).save();
    context.fileStorageService().storeFile(simpleInputStream(), new FileDescriptor(filename), true);
    assertThat(context.fileStorageService().findByPrefix(filename).size(), is(1));
  }

  private void assertAllFilesAreCorrupt(List<FileStorageItem> corruptFiles) {
    for (FileStorageItem file : corruptFiles) {
      if (file.getFilename() != null && file.getRepo() != null)
        throw new AssertionError("Found item that is not corrupt.");
    }
  }

  private void setMetadataToNull(String reponame) {
    GridFsFileStorageItem fileStorageItem = (GridFsFileStorageItem) context.fileStorageService().getAllRpms(reponame).get(0);
    context.mongoTemplate().updateFirst(query(where("_id").is(fileStorageItem.getId())), update("metadata", null), GRIDFS_FILES_COLLECTION);
    assertThat(context.fileStorageService().findById(fileStorageItem.getId()).getRepo(), nullValue());
  }

  private void setFilenameToNull(String reponame) {
    GridFsFileStorageItem fileStorageItem = (GridFsFileStorageItem) context.fileStorageService().getAllRpms(reponame).get(0);
    context.mongoTemplate().updateFirst(query(where("_id").is(fileStorageItem.getId())), update("filename", null), GRIDFS_FILES_COLLECTION);
    assertThat(context.fileStorageService().findById(fileStorageItem.getId()).getFilename(), nullValue());
  }

  private void givenTowOfThreeFilesToBeDeleted(final Date now) throws IOException {
    final String repoToDeleteIn = uniqueRepoName();
    final Date past = addDays(now, -1);
    givenFileToBeDeleted(new FileDescriptor(repoToDeleteIn, TESTING_ARCH, "toBeDeletedPast1"), past);
    givenFileToBeDeleted(new FileDescriptor(repoToDeleteIn, TESTING_ARCH, "toBeDeletedPast2"), past);
    givenFileToBeDeleted(new FileDescriptor(repoToDeleteIn, TESTING_ARCH, "toBeDeletedFuture"), addDays(now, 1));
  }

  private GridFSFile givenFileToBeDeleted(FileDescriptor descriptor, final Date time) throws IOException {
    final GridFSDBFile toBeDeleted = ((GridFsFileStorageItem) context.storageTestUtils().givenFileWithDescriptor(descriptor)).getDbFile();

    mergeMetaData(toBeDeleted, new BasicDBObject(MARKED_AS_DELETED_KEY, time));
    toBeDeleted.save();
    return toBeDeleted;
  }
}
