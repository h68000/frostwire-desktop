package com.frostwire.gui.library;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.frostwire.alexandria.Playlist;
import com.frostwire.alexandria.PlaylistItem;
import com.frostwire.gui.bittorrent.TorrentUtil;
import com.frostwire.gui.components.IconSearchField;
import com.limegroup.gnutella.MediaType;
import com.limegroup.gnutella.gui.GUIMediator;
import com.limegroup.gnutella.gui.I18n;
import com.limegroup.gnutella.gui.search.SearchInformation;
import com.limegroup.gnutella.gui.search.SearchMediator;
import com.limegroup.gnutella.gui.util.BackgroundExecutorService;
import com.limegroup.gnutella.settings.SharingSettings;

public class LibrarySearch extends JPanel {

    private static final long serialVersionUID = 2266243762191789491L;

    private JLabel statusLabel;
    private IconSearchField searchField;

    private SearchRunnable currentSearchRunnable;

    private int resultsCount;

    public LibrarySearch() {
        setupUI();
    }

    public void addResults(int n) {
        if (n < 0) {
            return;
        }

        resultsCount += n;
        statusLabel.setText(resultsCount + " " + I18n.tr("search results"));
    }

    public void clear() {
        statusLabel.setText("");
        searchField.setText("");
        resultsCount = 0;
    }

    protected void setupUI() {
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));

        GridBagConstraints c;

        statusLabel = new JLabel();
        c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.gridwidth = GridBagConstraints.RELATIVE;
        c.weightx = 1;
        add(statusLabel, c);

        searchField = new IconSearchField(10, GUIMediator.getThemeImage("search_tab"));
        c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_END;
        c.gridwidth = GridBagConstraints.REMAINDER;
        add(searchField, c);

        searchField.addKeyListener(new KeyAdapter() {
            private Action a = new SearchLibraryAction();

            private long lastSearch;

            @Override
            public void keyReleased(KeyEvent e) {
                if (searchField.getText().length() == 0 && (e.getKeyCode() == KeyEvent.VK_BACK_SPACE || e.getKeyCode() == KeyEvent.VK_DELETE)) {
                    lastSearch = 0;
                    searchField.setText(".");
                    a.actionPerformed(null);
                    searchField.setText("");
                }

                if (System.currentTimeMillis() - lastSearch > 125) {
                    a.actionPerformed(null);
                    lastSearch = System.currentTimeMillis();
                }
            }
        });
    }

    private class SearchLibraryAction extends AbstractAction {

        private static final long serialVersionUID = -2182314529781104010L;

        public SearchLibraryAction() {
            putValue(Action.NAME, I18n.tr("Search"));
        }

        public boolean validate(SearchInformation info) {
            switch (SearchMediator.validateInfo(info)) {
            case SearchMediator.QUERY_EMPTY:
                return false;
            case SearchMediator.QUERY_XML_TOO_LONG:
                // cannot happen
            case SearchMediator.QUERY_VALID:
            default:
                return true;
            }
        }

        public void actionPerformed(ActionEvent e) {
            String query = searchField.getText().trim();
            if (query.length() == 0) {
                searchField.getToolkit().beep();
                return;
            }
            final SearchInformation info = SearchInformation.createKeywordSearch(query, null, MediaType.getAnyTypeMediaType());
            if (!validate(info)) {
                return;
            }
            searchField.addToDictionary();

            if (currentSearchRunnable != null) {
                currentSearchRunnable.cancel();
            }
            
            DirectoryHolder directoryHolder = LibraryMediator.instance().getLibraryFiles().getSelectedDirectoryHolder();
            if (directoryHolder != null) {
                currentSearchRunnable = new SearchFilesRunnable(query);
                BackgroundExecutorService.schedule(currentSearchRunnable);
            }
            
            Playlist playlist = LibraryMediator.instance().getLibraryPlaylists().getSelectedPlaylist();
            if (playlist != null) {
                currentSearchRunnable = new SearchPlaylistItemsRunnable(query);
                BackgroundExecutorService.schedule(currentSearchRunnable);
            }
        }
    }
    
    private abstract class SearchRunnable implements Runnable {
        protected boolean canceled;
        
        public void cancel() {
            canceled = true;
        }
    }

    private final class SearchFilesRunnable extends SearchRunnable {

        private final String _query;
        private final DirectoryHolder directoryHolder;
        
        public SearchFilesRunnable(String query) {
            _query = query;
            directoryHolder = LibraryMediator.instance().getLibraryFiles().getSelectedDirectoryHolder();
            canceled = false;
            
            // weird case
            if (directoryHolder == null) {
                canceled = true;
            }
        }

        public void run() {
            if (canceled) {
                return;
            }
            // special case for Finished Downloads
            if (_query.equals(".") && directoryHolder instanceof SavedFilesDirectoryHolder) {
                GUIMediator.safeInvokeLater(new Runnable() {
                    public void run() {
                        LibraryMediator.instance().updateTableFiles(directoryHolder);
                        statusLabel.setText("");
                        resultsCount = 0;
                    }
                });
                return;
            }

            GUIMediator.safeInvokeLater(new Runnable() {
                public void run() {
                    LibraryFilesTableMediator.instance().clearTable();
                    statusLabel.setText("");
                    resultsCount = 0;
                }
            });

            File file = SharingSettings.TORRENT_DATA_DIR_SETTING.getValue();
            if (directoryHolder instanceof TorrentDirectoryHolder) {
                file = ((TorrentDirectoryHolder) directoryHolder).getDirectory();
            }

            Set<File> ignore = TorrentUtil.getIncompleteFiles();
            ignore.addAll(TorrentUtil.getSkipedFiles());

            search(file, ignore);
        }

        /**
         * It searches _query in haystackDir.
         * 
         * @param haystackDir
         * @param excludeFiles - Usually a list of incomplete files.
         */
        private void search(File haystackDir, Set<File> excludeFiles) {
            if (canceled) {
                return;
            }

            if (!haystackDir.isDirectory()) {
                return;
            }

            final List<File> directories = new ArrayList<File>();
            final List<File> results = new ArrayList<File>();
            SearchFileFilter searchFilter = new SearchFileFilter(_query);

            for (File child : haystackDir.listFiles(searchFilter)) {
                if (canceled) {
                    return;
                }

                /////
                //Stop search if the user selected another item in the library tree
                DirectoryHolder currentDirectoryHolder = LibraryMediator.instance().getLibraryFiles().getSelectedDirectoryHolder();
                if (!directoryHolder.equals(currentDirectoryHolder)) {
                    return;
                }
                /////

                if (excludeFiles.contains(child)) {
                    continue;
                }
                if (child.isHidden()) {
                    continue;
                }

                if (child.isDirectory()) {
                    directories.add(child);

                    //                    if (searchFilter.accept(child, false)) {
                    //                        results.add(child);
                    //                    }

                } else if (child.isFile() && directoryHolder.accept(child)) {
                    results.add(child);
                }
            }

            Runnable r = new Runnable() {
                public void run() {
                    LibraryMediator.instance().addFilesToLibraryTable(results);
                }
            };
            GUIMediator.safeInvokeLater(r);

            for (File directory : directories) {
                search(directory, excludeFiles);
            }
        }
    }

    private static final class SearchFileFilter implements FileFilter {

        private final String[] _tokens;

        public SearchFileFilter(String query) {
            _tokens = query.toLowerCase(Locale.US).split(" ");
        }

        public boolean accept(File pathname) {
            return accept(pathname, true);
        }

        /**
         * 
         * @param pathname
         * @param includeAllDirectories - if true, it will say TRUE to any directory
         * @return
         */
        public boolean accept(File pathname, boolean includeAllDirectories) {

            if (pathname.isDirectory() && includeAllDirectories) {
                return true;
            }

            String name = pathname.getName();

            for (String token : _tokens) {
                if (!name.toLowerCase(Locale.US).contains(token)) {
                    return false;
                }
            }

            return true;
        }
    }
    
    private final class SearchPlaylistItemsRunnable extends SearchRunnable {

        private final String query;
        private final Playlist playlist;
        
        public SearchPlaylistItemsRunnable(String query) {
            this.query = query;
            playlist = LibraryMediator.instance().getLibraryPlaylists().getSelectedPlaylist();
            canceled = false;
            
            // weird case
            if (playlist == null) {
                canceled = true;
            }
        }

        public void run() {
            if (canceled) {
                return;
            }

            GUIMediator.safeInvokeLater(new Runnable() {
                public void run() {
                    LibraryPlaylistsTableMediator.instance().clearTable();
                    statusLabel.setText("");
                    resultsCount = 0;
                }
            });

            search();
        }

        private void search() {
            if (canceled) {
                return;
            }

            //FULL TEXT SEARCH, Returns the File IDs we care about.
            String fullTextIndexSql = "SELECT * FROM FT_SEARCH('"+query+"', 0, 0)";
            
            List<List<Object>> matchedFileRows = LibraryMediator.getLibrary().getDB().getDatabase().query(fullTextIndexSql);
            
            int fileIDStrOffset = " PUBLIC   PLAYLISTITEMS  WHERE  PLAYLISTITEMID =".length();
            
            StringBuilder fileIDSet = new StringBuilder("(");
            
            int numFilesFound = matchedFileRows.size();
            int i = 0;
            
            for (List<Object> row : matchedFileRows) {
                String rowStr = (String) row.get(0);
                fileIDSet.append(rowStr.substring(fileIDStrOffset));
                
                if (i++ < (numFilesFound-1)) {
                    fileIDSet.append(",");
                }
            }
            fileIDSet.append(")");
            
            String sql = "SELECT playlistItemId, filePath, fileName, fileSize, fileExtension, trackTitle, duration, artistName, albumName, coverArtPath, bitrate, comment, genre, track, year FROM PlaylistItems WHERE playlistItemId IN "+ fileIDSet.toString();
            List<List<Object>> rows = LibraryMediator.getLibrary().getDB().getDatabase().query(sql);
            
            final List<PlaylistItem> results = new ArrayList<PlaylistItem>();

            for (List<Object> row : rows) {
                if (canceled) {
                    return;
                }

                /////
                //Stop search if the user selected another item in the playlist list
                Playlist currentPlaylist = LibraryMediator.instance().getLibraryPlaylists().getSelectedPlaylist();
                if (!playlist.equals(currentPlaylist)) {
                    return;
                }
                /////
                
                PlaylistItem item = new PlaylistItem(null);
                item.getDB().fill(row, item);
                results.add(item);
                
                if (results.size() > 100) {
                    Runnable r = new Runnable() {
                        public void run() {
                            LibraryMediator.instance().addItemsToLibraryTable(results);
                            results.clear();
                        }
                    };
                    GUIMediator.safeInvokeLater(r);
                }
            }

            Runnable r = new Runnable() {
                public void run() {
                    LibraryMediator.instance().addItemsToLibraryTable(results);
                }
            };
            GUIMediator.safeInvokeLater(r);
        }
    }
}