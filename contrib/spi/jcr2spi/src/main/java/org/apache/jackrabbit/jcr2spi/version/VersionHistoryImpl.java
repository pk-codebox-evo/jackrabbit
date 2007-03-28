/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.jcr2spi.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jackrabbit.jcr2spi.NodeImpl;
import org.apache.jackrabbit.jcr2spi.ItemManager;
import org.apache.jackrabbit.jcr2spi.SessionImpl;
import org.apache.jackrabbit.jcr2spi.ItemLifeCycleListener;
import org.apache.jackrabbit.jcr2spi.LazyItemIterator;
import org.apache.jackrabbit.jcr2spi.hierarchy.NodeEntry;
import org.apache.jackrabbit.jcr2spi.hierarchy.PropertyEntry;
import org.apache.jackrabbit.jcr2spi.state.NodeState;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NameException;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.NameFormat;

import javax.jcr.version.VersionHistory;
import javax.jcr.version.Version;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionException;
import javax.jcr.RepositoryException;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.AccessDeniedException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.nodetype.ConstraintViolationException;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

/**
 * <code>VersionHistoryImpl</code>...
 */
public class VersionHistoryImpl extends NodeImpl implements VersionHistory {

    private static Logger log = LoggerFactory.getLogger(VersionHistoryImpl.class);

    private final NodeEntry vhEntry;
    private final NodeEntry labelNodeEntry;

    public VersionHistoryImpl(ItemManager itemMgr, SessionImpl session,
                              NodeState state, ItemLifeCycleListener[] listeners)
        throws VersionException, RepositoryException {
        super(itemMgr, session, state, listeners);
        this.vhEntry = (NodeEntry) state.getHierarchyEntry();

        // retrieve nodestate of the jcr:versionLabels node
        if (vhEntry.hasNodeEntry(QName.JCR_VERSIONLABELS)) {
            labelNodeEntry = vhEntry.getNodeEntry(QName.JCR_VERSIONLABELS, Path.INDEX_DEFAULT);
        } else {
            throw new VersionException("nt:versionHistory requires a mandatory, autocreated child node jcr:versionLabels.");
        }
    }

    //-----------------------------------------------------< VersionHistory >---
    /**
     *
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getVersionableUUID()
     */
    public String getVersionableUUID() throws RepositoryException {
        checkStatus();
        return getProperty(QName.JCR_VERSIONABLEUUID).getString();
    }

    /**
     *
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getRootVersion()
     */
    public Version getRootVersion() throws RepositoryException {
        checkStatus();
        if (vhEntry.hasNodeEntry(QName.JCR_ROOTVERSION)) {
            NodeEntry vEntry = vhEntry.getNodeEntry(QName.JCR_ROOTVERSION, Path.INDEX_DEFAULT);
            return (Version) itemMgr.getItem(vEntry);
        } else {
            String msg = "Unexpected error: VersionHistory state does not contain a root version child node entry.";
            log.error(msg);
            throw new RepositoryException(msg);
        }
    }

    /**
     *
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getAllVersions()
     */
    public VersionIterator getAllVersions() throws RepositoryException {
        checkStatus();
        refreshEntry(vhEntry);
        Iterator childIter = vhEntry.getNodeEntries();
        List versionEntries = new ArrayList();
        // all child-nodes except from jcr:versionLabels point to Versions.
        while (childIter.hasNext()) {
            NodeEntry entry = (NodeEntry) childIter.next();
            if (!QName.JCR_VERSIONLABELS.equals(entry.getQName())) {
                versionEntries.add(entry);
            }
        }
        return new LazyItemIterator(itemMgr, versionEntries.iterator());
    }

    /**
     *
     * @param versionName
     * @return
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#getVersion(String)
     */
    public Version getVersion(String versionName) throws VersionException, RepositoryException {
        checkStatus();
        NodeState vState = getVersionState(versionName);
        return (Version) itemMgr.getItem(vState.getHierarchyEntry());
    }

    /**
     *
     * @param label
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getVersionByLabel(String)
     */
    public Version getVersionByLabel(String label) throws RepositoryException {
        checkStatus();
        NodeState vState = getVersionStateByLabel(getQLabel(label));
        return (Version) itemMgr.getItem(vState.getHierarchyEntry());
    }

    /**
     *
     * @param versionName
     * @param label
     * @param moveLabel
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#addVersionLabel(String, String, boolean)
     */
    public void addVersionLabel(String versionName, String label, boolean moveLabel) throws VersionException, RepositoryException {
        checkStatus();
        QName qLabel = getQLabel(label);
        NodeState vState = getVersionState(versionName);
        // delegate to version manager that operates on workspace directely
        session.getVersionManager().addVersionLabel((NodeState) getItemState(), vState, qLabel, moveLabel);
    }

    /**
     *
     * @param label
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#removeVersionLabel(String)
     */
    public void removeVersionLabel(String label) throws VersionException, RepositoryException {
        checkStatus();
        QName qLabel = getQLabel(label);
        NodeState vState = getVersionStateByLabel(getQLabel(label));
        // delegate to version manager that operates on workspace directely
        session.getVersionManager().removeVersionLabel((NodeState) getItemState(), vState, qLabel);
    }

    /**
     *
     * @param label
     * @return
     * @throws RepositoryException
     * @see VersionHistory#hasVersionLabel(String)
     */
    public boolean hasVersionLabel(String label) throws RepositoryException {
        checkStatus();
        QName l = getQLabel(label);
        QName[] qLabels = getQLabels();
        for (int i = 0; i < qLabels.length; i++) {
            if (qLabels[i].equals(l)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param version
     * @param label
     * @return
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#hasVersionLabel(Version, String)
     */
    public boolean hasVersionLabel(Version version, String label) throws VersionException, RepositoryException {
        // check-status performed within checkValidVersion
        checkValidVersion(version);
        String vUUID = version.getUUID();
        QName l = getQLabel(label);

        QName[] qLabels = getQLabels();
        for (int i = 0; i < qLabels.length; i++) {
            if (qLabels[i].equals(l)) {
                String uuid = getVersionStateByLabel(qLabels[i]).getUniqueID();
                return vUUID.equals(uuid);
            }
        }
        return false;
    }

    /**
     *
     * @return
     * @throws RepositoryException
     * @see VersionHistory#getVersionLabels()
     */
    public String[] getVersionLabels() throws RepositoryException {
        checkStatus();
        QName[] qLabels = getQLabels();
        String[] labels = new String[qLabels.length];

        for (int i = 0; i < qLabels.length; i++) {
            try {
                labels[i] = NameFormat.format(qLabels[i], session.getNamespaceResolver());
            } catch (NoPrefixDeclaredException e) {
                // unexpected error. should not occur.
                throw new RepositoryException(e);
            }
        }
        return labels;
    }

    /**
     *
     * @param version
     * @return
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#getVersionLabels(Version)
     */
    public String[] getVersionLabels(Version version) throws VersionException, RepositoryException {
        // check-status performed within checkValidVersion
        checkValidVersion(version);
        String vUUID = version.getUUID();

        List vlabels = new ArrayList();
        QName[] qLabels = getQLabels();
        for (int i = 0; i < qLabels.length; i++) {
            String uuid = getVersionStateByLabel(qLabels[i]).getUniqueID();
            if (vUUID.equals(uuid)) {
                try {
                    vlabels.add(NameFormat.format(qLabels[i], session.getNamespaceResolver()));
                } catch (NoPrefixDeclaredException e) {
                    // should never occur
                    throw new RepositoryException("Unexpected error while accessing version label", e);
                }
            }
        }
        return (String[]) vlabels.toArray(new String[vlabels.size()]);
    }

    /**
     *
     * @param versionName
     * @throws ReferentialIntegrityException
     * @throws AccessDeniedException
     * @throws UnsupportedRepositoryOperationException
     * @throws VersionException
     * @throws RepositoryException
     * @see VersionHistory#removeVersion(String)
     */
    public void removeVersion(String versionName) throws ReferentialIntegrityException,
        AccessDeniedException, UnsupportedRepositoryOperationException,
        VersionException, RepositoryException {
        checkStatus();
        NodeState vState = getVersionState(versionName);
        session.getVersionManager().removeVersion((NodeState) getItemState(), vState);
    }

    //---------------------------------------------------------------< Item >---
    /**
     *
     * @param otherItem
     * @return
     * @see Item#isSame(Item)
     */
    public boolean isSame(Item otherItem) throws RepositoryException {
        checkStatus();
        if (otherItem instanceof VersionHistoryImpl) {
            // since all version histories are referenceable, protected and live
            // in the same workspace, a simple comparison of the UUIDs is sufficient.
            VersionHistoryImpl other = ((VersionHistoryImpl) otherItem);
            return vhEntry.getUniqueID().equals(other.vhEntry.getUniqueID());
        }
        return false;
    }

    //-----------------------------------------------------------< ItemImpl >---
    /**
     *
     * @throws UnsupportedRepositoryOperationException
     * @throws ConstraintViolationException
     * @throws RepositoryException
     */
    protected void checkIsWritable() throws UnsupportedRepositoryOperationException, ConstraintViolationException, RepositoryException {
        super.checkIsWritable();
        throw new ConstraintViolationException("VersionHistory is protected");
    }

    /**
     * Always returns false
     *
     * @throws RepositoryException
     * @see NodeImpl#isWritable()
     */
    protected boolean isWritable() throws RepositoryException {
        super.isWritable();
        return false;
    }
    //------------------------------------------------------------< private >---
    /**
     *
     * @return
     */
    private QName[] getQLabels() throws RepositoryException {
        refreshEntry(labelNodeEntry);
        List labelQNames = new ArrayList();
        Iterator it = labelNodeEntry.getPropertyEntries();
        while (it.hasNext()) {
            PropertyEntry pe = (PropertyEntry) it.next();
            if (QName.JCR_PRIMARYTYPE.equals(pe.getQName())) {
                continue;
            }
            labelQNames.add(pe.getQName());
        }
        return (QName[]) labelQNames.toArray(new QName[labelQNames.size()]);
    }

    /**
     *
     * @param versionName
     * @return
     * @throws VersionException
     * @throws RepositoryException
     */
    private NodeState getVersionState(String versionName) throws VersionException, RepositoryException {
        try {
            QName vQName = NameFormat.parse(versionName, session.getNamespaceResolver());
            refreshEntry(vhEntry);
            NodeEntry vEntry = vhEntry.getNodeEntry(vQName, Path.INDEX_DEFAULT);
            if (vEntry == null) {
                throw new VersionException("Version '" + versionName + "' does not exist in this version history.");
            } else {
                return vEntry.getNodeState();
            }
        } catch (NameException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     *
     * @param qLabel
     * @return
     * @throws VersionException
     * @throws RepositoryException
     */
    private NodeState getVersionStateByLabel(QName qLabel) throws VersionException, RepositoryException {
        refreshEntry(labelNodeEntry);
        if (labelNodeEntry.hasPropertyEntry(qLabel)) {
            // retrieve reference property value -> and retrieve referenced node
            PropertyEntry pEntry = labelNodeEntry.getPropertyEntry(qLabel);
            Node version = ((Property) itemMgr.getItem(pEntry)).getNode();
            return getVersionState(version.getName());
        } else {
            throw new VersionException("Version with label '" + qLabel + "' does not exist.");
        }
    }

    /**
     *
     * @param label
     * @return
     * @throws RepositoryException
     */
    private QName getQLabel(String label) throws RepositoryException {
        try {
            return NameFormat.parse(label, session.getNamespaceResolver());
        } catch (NameException e) {
            String error = "Invalid version label: " + e.getMessage();
            log.error(error);
            throw new RepositoryException(error, e);
        }
    }

    /**
     * Checks if the specified version belongs to this <code>VersionHistory</code>.
     * This method throws <code>VersionException</code> if {@link Version#getContainingHistory()}
     * is not the same item than this <code>VersionHistory</code>.
     *
     * @param version
     * @throws javax.jcr.version.VersionException
     * @throws javax.jcr.RepositoryException
     */
    private void checkValidVersion(Version version) throws VersionException, RepositoryException {
        if (!version.getContainingHistory().isSame(this)) {
            throw new VersionException("Specified version '" + version.getName() + "' is not part of this history.");
        }
    }

    private void refreshEntry(NodeEntry entry) throws RepositoryException {
        // TODO: check again.. is this correct? or should NodeEntry be altered
        entry.getNodeState();
    }
}