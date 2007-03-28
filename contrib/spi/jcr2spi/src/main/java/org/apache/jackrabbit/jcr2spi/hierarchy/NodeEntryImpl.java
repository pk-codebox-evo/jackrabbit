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
package org.apache.jackrabbit.jcr2spi.hierarchy;

import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.spi.Event;
import org.apache.jackrabbit.spi.ItemId;
import org.apache.jackrabbit.spi.ChildInfo;
import org.apache.jackrabbit.spi.QNodeDefinition;
import org.apache.jackrabbit.spi.QPropertyDefinition;
import org.apache.jackrabbit.spi.IdFactory;
import org.apache.jackrabbit.spi.PropertyId;
import org.apache.jackrabbit.jcr2spi.state.NodeState;
import org.apache.jackrabbit.jcr2spi.state.ItemState;
import org.apache.jackrabbit.jcr2spi.state.ChangeLog;
import org.apache.jackrabbit.jcr2spi.state.Status;
import org.apache.jackrabbit.jcr2spi.state.PropertyState;
import org.apache.jackrabbit.jcr2spi.state.ItemStateLifeCycleListener;
import org.apache.jackrabbit.jcr2spi.util.StateUtility;
import org.apache.commons.collections.iterators.IteratorChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemExistsException;
import javax.jcr.RepositoryException;
import javax.jcr.PathNotFoundException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.InvalidItemStateException;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * <code>NodeEntryImpl</code> implements common functionality for child
 * node entry implementations.
 */
public class NodeEntryImpl extends HierarchyEntryImpl implements NodeEntry {

    private static Logger log = LoggerFactory.getLogger(NodeEntryImpl.class);

    /**
     * UniqueID identifying this NodeEntry or <code>null</code> if either
     * the underlying state has not been loaded yet or if it cannot be
     * identified with a unique ID.
     */
    private String uniqueID;

    /**
     * insertion-ordered collection of NodeEntry objects
     */
    private ChildNodeEntries childNodeEntries;

    /**
     * Map used to remember transiently removed or moved childNodeEntries, that
     * must not be retrieved from the persistent storage.
     */
    private ChildNodeAttic childNodeAttic;

    /**
     * Map of properties. Key = {@link QName} of property. Value = {@link
     * PropertyEntry}.
     */
    private final HashMap properties = new HashMap();

    /**
     * Map of properties which are deleted and have been re-created as transient
     * property with the same name.
     */
    private final HashMap propertiesInAttic = new HashMap();

    /**
     * Upon transient 'move' ('rename') or 'reorder' of SNSs this
     * <code>NodeEntry</code> remembers the original parent, name and index
     * for later revert as well as for the creation of the
     * {@link #getWorkspaceId() workspace id}. Finally the revertInfo is
     * used to find the target of an <code>Event</code> indicating external
     * modification.
     *
     * @see #refresh(Event)
     */
    private RevertInfo revertInfo;

    /**
     * Creates a new <code>NodeEntryImpl</code>
     *
     * @param parent    the <code>NodeEntry</code> that owns this child item
     *                  reference.
     * @param name      the name of the child node.
     * @param factory   the entry factory.
     */
    NodeEntryImpl(NodeEntryImpl parent, QName name, String uniqueID, EntryFactory factory) {
        super(parent, name, factory);
        this.uniqueID = uniqueID; // NOTE: don't use setUniqueID (for mod only)
        this.childNodeAttic = new ChildNodeAttic();

        factory.notifyEntryCreated(this);
    }

    /**
     *
     * @return
     */
    static NodeEntry createRootEntry(EntryFactory factory) {
        return new NodeEntryImpl(null, QName.ROOT, null, factory);
    }

    //-----------------------------------------------------< HierarchyEntry >---
    /**
     * Returns true.
     *
     * @inheritDoc
     * @see HierarchyEntry#denotesNode()
     */
    public boolean denotesNode() {
        return true;
    }

    /**
     * @inheritDoc
     * @see HierarchyEntry#invalidate(boolean)
     */
    public void invalidate(boolean recursive) {
        if (recursive) {
            // invalidate all child entries including properties present in the
            // attic (removed props shadowed by a new property with the same name).
            for (Iterator it = getAllChildEntries(false, true); it.hasNext();) {
                HierarchyEntry ce = (HierarchyEntry) it.next();
                ce.invalidate(recursive);
            }
        }
        // invalidate 'childNodeEntries'
        if (getStatus() != Status.NEW && childNodeEntries != null) {
            childNodeEntries.setStatus(ChildNodeEntries.STATUS_INVALIDATED);
        }
        // ... and invalidate the resolved state (if available)
        super.invalidate(recursive);
    }

    /**
     * If 'recursive' is true, the complete hierarchy below this entry is
     * traversed and reloaded. Otherwise only this entry and the direct
     * decendants are reloaded.
     *
     * @see HierarchyEntry#reload(boolean, boolean)
     */
    public void reload(boolean keepChanges, boolean recursive) {
        // reload this entry
        super.reload(keepChanges, recursive);

        // reload all children unless 'recursive' is false and the reload above
        // did not cause this entry to be removed -> therefore check status.
        if (recursive && !Status.isTerminal(getStatus())) {
            // recursivly reload all entries including props that are in the attic.
            for (Iterator it = getAllChildEntries(true, true); it.hasNext();) {
                HierarchyEntry ce = (HierarchyEntry) it.next();
                ce.reload(keepChanges, recursive);
            }
        }
    }

    /**
     * Calls {@link HierarchyEntryImpl#revert()} and moves all properties from the
     * attic back into th properties map. If this HierarchyEntry has been
     * transiently moved, it is in addition moved back to its old parent.
     * Similarly reordering of child node entries is reverted.
     *
     * @inheritDoc
     * @see HierarchyEntry#revert()
     */
    public void revert() throws RepositoryException {
        // move all properties from attic back to properties map
        if (!propertiesInAttic.isEmpty()) {
            properties.putAll(propertiesInAttic);
            propertiesInAttic.clear();
        }

        revertTransientChanges();

        // now make sure the underlying state is reverted to the original state
        super.revert();
    }

    /**
     * @see HierarchyEntry#transientRemove()
     */
    public void transientRemove() throws RepositoryException {
        for (Iterator it = getAllChildEntries(true, false); it.hasNext();) {
            HierarchyEntry ce = (HierarchyEntry) it.next();
            ce.transientRemove();
        }

        if (!propertiesInAttic.isEmpty()) {
            // move all properties from attic back to properties map
            properties.putAll(propertiesInAttic);
            propertiesInAttic.clear();
        }

        // execute for this entry as well
        super.transientRemove();
    }

    /**
     * @see HierarchyEntry#remove()
     */
    public void remove() {
        removeEntry(this);
        if (getStatus() != Status.STALE_DESTROYED && parent.childNodeEntries != null) {
            NodeEntry removed = parent.childNodeEntries.remove(this);
            if (removed == null) {
                // try attic
                parent.childNodeAttic.remove(this);
            }
        }

        // TODO: deal with childNodeAttic
        // now traverse all child-entries and mark the attached states removed
        // without removing the child-entries themselves. this is not required
        // since this (i.e. the parent is removed as well).
        for (Iterator it = getAllChildEntries(true, true); it.hasNext();) {
            HierarchyEntryImpl ce = (HierarchyEntryImpl) it.next();
            removeEntry(ce);
        }
    }

    /**
     * If the underlying state is available and transiently modified, new or
     * stale, it gets added to the changeLog. Subsequently this call is repeated
     * recursively to collect all child states that meet the condition,
     * including those property states that have been moved to the attic.
     *
     * @inheritDoc
     * @see HierarchyEntry#collectStates(ChangeLog, boolean)
     */
    public synchronized void collectStates(ChangeLog changeLog, boolean throwOnStale) throws InvalidItemStateException {
        super.collectStates(changeLog, throwOnStale);

        // collect transient child states including properties in attic.
        for (Iterator it = getAllChildEntries(true, true); it.hasNext();) {
            HierarchyEntry ce = (HierarchyEntry) it.next();
            ce.collectStates(changeLog, throwOnStale);
        }
    }

    //----------------------------------------------------------< NodeEntry >---
    /**
     * @inheritDoc
     * @see NodeEntry#getId()
     */
    public NodeId getId() {
        IdFactory idFactory = factory.getIdFactory();
        if (uniqueID != null) {
            return idFactory.createNodeId(uniqueID);
        } else {
            if (parent == null) {
                // root node
                return idFactory.createNodeId((String) null, Path.ROOT);
            } else {
                return idFactory.createNodeId(parent.getId(), Path.create(getQName(), getIndex()));
            }
        }
    }

    /**
     * @see NodeEntry#getWorkspaceId()
     */
    public NodeId getWorkspaceId() {
        IdFactory idFactory = factory.getIdFactory();
        if (uniqueID != null || parent == null) {
            // uniqueID and root-node -> internal id is always the same as getId().
            return getId();
        } else {
            NodeId parentId = (revertInfo != null) ? revertInfo.oldParent.getWorkspaceId() : parent.getWorkspaceId();
            return idFactory.createNodeId(parentId, Path.create(getWorkspaceQName(), getWorkspaceIndex()));
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#getUniqueID()
     */
    public String getUniqueID() {
        return uniqueID;
    }

    /**
     * @inheritDoc
     * @see NodeEntry#setUniqueID(String)
     */
    public void setUniqueID(String uniqueID) {
        String old = this.uniqueID;
        boolean mod = (uniqueID == null) ? old != null : !uniqueID.equals(old);
        if (mod) {
            this.uniqueID = uniqueID;
            factory.notifyIdChange(this, old);
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#getIndex()
     */
    public int getIndex() {
        if (parent == null) {
            // the root state may never have siblings
            return Path.INDEX_DEFAULT;
        }

        NodeState state = (NodeState) internalGetItemState();
        try {
            if (state == null || state.getDefinition().allowsSameNameSiblings()) {
                return parent.getChildIndex(this);
            } else {
                return Path.INDEX_DEFAULT;
            }
        } catch (RepositoryException e) {
            log.error("Error while building Index. ", e.getMessage());
            return Path.INDEX_UNDEFINED;
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#getNodeState()
     */
    public NodeState getNodeState() throws ItemNotFoundException, RepositoryException {
        return (NodeState) getItemState();
    }

    /**
     * @inheritDoc
     * @see NodeEntry#getDeepEntry(Path)
     */
    public HierarchyEntry getDeepEntry(Path path) throws PathNotFoundException, RepositoryException {
        NodeEntryImpl entry = this;
        Path.PathElement[] elems = path.getElements();
        for (int i = 0; i < elems.length; i++) {
            Path.PathElement elem = elems[i];
            // check for root element
            if (elem.denotesRoot()) {
                if (getParent() != null) {
                    throw new RepositoryException("NodeEntry out of 'hierarchy'" + path.toString());
                } else {
                    continue;
                }
            }

            int index = elem.getNormalizedIndex();
            QName name = elem.getName();

            // first try to resolve to nodeEntry or property entry
            NodeEntry cne = (entry.childNodeEntries == null) ? null : entry.childNodeEntries.get(name, index);
            if (cne != null) {
                entry = (NodeEntryImpl) cne;
            } else if (index == Path.INDEX_DEFAULT && entry.properties.containsKey(name)
                && i == path.getLength() - 1) {
                // property must not have index && must be final path element
                return (PropertyEntry) entry.properties.get(name);
            } else {
                // no valid entry
                // -> check for moved child entry in node-attic
                // -> check if child points to a removed sns
                if (entry.childNodeAttic.contains(name, index)) {
                    throw new PathNotFoundException(path.toString());
                } else if (entry.childNodeEntries != null) {
                    int noSNS = entry.childNodeEntries.get(name).size() + entry.childNodeAttic.get(name).size();
                    if (index <= noSNS) {
                        throw new PathNotFoundException(path.toString());
                    }
                }
               /*
                * Unknown entry (not-existing or not yet loaded):
                * Skip all intermediate entries and directly try to load the ItemState
                * (including building the itermediate entries. If that fails
                * ItemNotFoundException is thrown.
                *
                * Since 'path' might be ambigous (Node or Property):
                * 1) first try Node
                * 2) if the NameElement does not have SNS-index => try Property
                * 3) else throw
                */
                Path remainingPath;
                try {
                    Path.PathBuilder pb = new Path.PathBuilder();
                    for (int j = i; j < elems.length; j++) {
                        pb.addLast(elems[j]);
                    }
                    remainingPath = pb.getPath();
                } catch (MalformedPathException e) {
                    // should not get here
                    throw new RepositoryException("Invalid path");
                }

                NodeId anyId = entry.getId();
                IdFactory idFactory = entry.factory.getIdFactory();
                NodeId nodeId = idFactory.createNodeId(anyId, remainingPath);
                try {
                    NodeState state = entry.factory.getItemStateFactory().createDeepNodeState(nodeId, entry);
                    return state.getHierarchyEntry();
                } catch (ItemNotFoundException e) {
                    if (index != Path.INDEX_DEFAULT) {
                        throw new PathNotFoundException(path.toString(), e);
                    }
                    // possibly  propstate
                    try {
                        nodeId = (remainingPath.getLength() == 1) ? anyId : idFactory.createNodeId(anyId, remainingPath.getAncestor(1));
                        PropertyId id = idFactory.createPropertyId(nodeId, remainingPath.getNameElement().getName());
                        PropertyState state = entry.factory.getItemStateFactory().createDeepPropertyState(id, entry);
                        return state.getHierarchyEntry();
                    } catch (ItemNotFoundException ise) {
                        throw new PathNotFoundException(path.toString());
                    }
                }
            }
        }
        return entry;
    }

    /**
     * @see NodeEntry#lookupDeepEntry(Path)
     */
    public HierarchyEntry lookupDeepEntry(Path workspacePath) {
        NodeEntryImpl entry = this;
        for (int i = 0; i < workspacePath.getLength(); i++) {
            Path.PathElement elem = workspacePath.getElement(i);
            // check for root element
            if (elem.denotesRoot()) {
                if (getParent() != null) {
                    log.warn("NodeEntry out of 'hierarchy'" + workspacePath.toString());
                    return null;
                } else {
                    continue;
                }
            }

            int index = elem.getNormalizedIndex();
            QName childName = elem.getName();

            // first try to resolve node
            NodeEntry cne = entry.lookupNodeEntry(childName, index);
            if (cne != null) {
                entry = (NodeEntryImpl) cne;
            } else if (index == Path.INDEX_DEFAULT && i == workspacePath.getLength() - 1) {
                // property must not have index && must be final path element
                return entry.lookupPropertyEntry(childName);
            } else {
                return null;
            }
        }
        return entry;
    }

    /**
     * @inheritDoc
     * @see NodeEntry#hasNodeEntry(QName)
     */
    public synchronized boolean hasNodeEntry(QName nodeName) {
        try {
            List namedEntries = childNodeEntries().get(nodeName);
            if (namedEntries.isEmpty()) {
                return false;
            } else {
                // copy list since during validation the childNodeEntries may be
                // modified if upon NodeEntry.getItemState the entry is removed.
                List l = new ArrayList(namedEntries.size());
                l.addAll(namedEntries);
                return EntryValidation.containsValidNodeEntry(l.iterator());
            }
        } catch (RepositoryException e) {
            log.debug("Unable to determine if a child node with name " + nodeName + " exists.");
            return false;
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#hasNodeEntry(QName, int)
     */
    public synchronized boolean hasNodeEntry(QName nodeName, int index) {
        try {
            return EntryValidation.isValidNodeEntry(childNodeEntries().get(nodeName, index));
        } catch (RepositoryException e) {
            log.debug("Unable to determine if a child node with name " + nodeName + " exists.");
            return false;
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#getNodeEntry(QName, int)
     */
    public synchronized NodeEntry getNodeEntry(QName nodeName, int index) throws RepositoryException {
        NodeEntry cne = childNodeEntries().get(nodeName, index);
        if (EntryValidation.isValidNodeEntry(cne)) {
            return cne;
        } else {
            return null;
        }
    }


    /**
     * @inheritDoc
     * @see NodeEntry#getNodeEntry(NodeId)
     */
    public synchronized NodeEntry getNodeEntry(NodeId childId) throws RepositoryException {
        String uid = childId.getUniqueID();
        Path path = childId.getPath();
        NodeEntry cne;
        if (uid != null && path == null) {
            // retrieve child-entry by uid
            cne = childNodeEntries().get(null, uid);
        } else {
           // retrieve child-entry by name and index
            Path.PathElement nameElement = path.getNameElement();
            cne = childNodeEntries().get(nameElement.getName(), nameElement.getIndex());
        }

        if (EntryValidation.isValidNodeEntry(cne)) {
            return cne;
        } else {
            return null;
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#getNodeEntries()
     */
    public synchronized Iterator getNodeEntries() throws RepositoryException {
        Collection entries = new ArrayList();
        Object[] arr = childNodeEntries().toArray();
        for (int i = 0; i < arr.length; i++) {
            NodeEntry cne = (NodeEntry) arr[i];
            if (EntryValidation.isValidNodeEntry(cne)) {
                entries.add(cne);
            }
        }
        return Collections.unmodifiableCollection(entries).iterator();
    }

    /**
     * @see NodeEntry#getNodeEntries(QName)
     */
    public synchronized List getNodeEntries(QName nodeName) throws RepositoryException {
        List namedEntries = childNodeEntries().get(nodeName);
        if (namedEntries.isEmpty()) {
            return Collections.EMPTY_LIST;
        } else {
            List entries = new ArrayList();
            // get array of the list, since during validation the childNodeEntries
            // may be modified if upon NodeEntry.getItemState the entry gets removed.
            Object[] arr = namedEntries.toArray();
            for (int i = 0; i < arr.length; i++) {
                NodeEntry cne = (NodeEntry) arr[i];
                if (EntryValidation.isValidNodeEntry(cne)) {
                    entries.add(cne);
                }
            }
            return Collections.unmodifiableList(entries);
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#addNodeEntry(QName, String, int)
     */
    public NodeEntry addNodeEntry(QName nodeName, String uniqueID, int index) throws RepositoryException {
        return internalAddNodeEntry(nodeName, uniqueID, index, childNodeEntries());
    }

    /**
     * @inheritDoc
     * @see NodeEntry#addNewNodeEntry(QName, String, QName, QNodeDefinition)
     */
    public NodeState addNewNodeEntry(QName nodeName, String uniqueID,
                                     QName primaryNodeType, QNodeDefinition definition) throws RepositoryException {
        NodeEntryImpl entry = internalAddNodeEntry(nodeName, uniqueID, Path.INDEX_UNDEFINED, childNodeEntries());
        NodeState state = factory.getItemStateFactory().createNewNodeState(entry, primaryNodeType, definition);
        entry.internalSetItemState(state);
        return state;
    }

    /**
     *
     * @param nodeName
     * @param uniqueID
     * @param index
     * @param childEntries
     * @return
     */
    private NodeEntryImpl internalAddNodeEntry(QName nodeName, String uniqueID,
                                               int index, ChildNodeEntries childEntries) {
        NodeEntryImpl entry = new NodeEntryImpl(this, nodeName, uniqueID, factory);
        childEntries.add(entry, index);
        return entry;
    }

    /**
     * @inheritDoc
     * @see NodeEntry#hasPropertyEntry(QName)
     */
    public synchronized boolean hasPropertyEntry(QName propName) {
        PropertyEntry entry = (PropertyEntry) properties.get(propName);
        return EntryValidation.isValidPropertyEntry(entry);
    }

    /**
     * @inheritDoc
     * @see NodeEntry#getPropertyEntry(QName)
     */
    public synchronized PropertyEntry getPropertyEntry(QName propName) {
        PropertyEntry entry = (PropertyEntry) properties.get(propName);
        if (EntryValidation.isValidPropertyEntry(entry)) {
            return entry;
        } else {
            return null;
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#getPropertyEntries()
     */
    public synchronized Iterator getPropertyEntries() {
        Collection props;
        if (getStatus() == Status.EXISTING_MODIFIED) {
            // filter out removed properties
            props = new ArrayList();
            // use array since upon validation the entry might be removed.
            Object[] arr = properties.values().toArray();
            for (int i = 0; i < arr.length; i++) {
                PropertyEntry propEntry = (PropertyEntry) arr[i];
                if (EntryValidation.isValidPropertyEntry(propEntry)) {
                    props.add(propEntry);
                }
            }
        } else {
            // no need to filter out properties, there are no removed properties
            props = properties.values();
        }
        return Collections.unmodifiableCollection(props).iterator();
    }

    /**
     * @inheritDoc
     * @see NodeEntry#addPropertyEntry(QName)
     */
    public PropertyEntry addPropertyEntry(QName propName) throws ItemExistsException {
        // TODO: check for existing prop.
        return internalAddPropertyEntry(propName);
    }

    /**
     * Internal method that adds a PropertyEntry without checking of that entry
     * exists.
     *
     * @param propName
     * @return
     */
    private PropertyEntry internalAddPropertyEntry(QName propName) {
        PropertyEntry entry = PropertyEntryImpl.create(this, propName, factory);
        properties.put(propName, entry);

        // if property-name is jcr:uuid or jcr:mixin this affects this entry
        // and the attached nodeState.
        if (StateUtility.isUuidOrMixin(propName)) {
            notifyUUIDorMIXINModified(entry);
        }
        return entry;
    }

    /**
     * @inheritDoc
     * @see NodeEntry#addPropertyEntries(Collection)
     */
    public void addPropertyEntries(Collection propNames) throws ItemExistsException, RepositoryException {
        Set diff = new HashSet();
        diff.addAll(properties.keySet());
        boolean containsExtra = diff.removeAll(propNames);

        // add all entries that are missing
        for (Iterator it = propNames.iterator(); it.hasNext();) {
            QName propName = (QName) it.next();
            if (!properties.containsKey(propName)) {
                addPropertyEntry(propName);
            }
        }

        // if this entry has not yet been resolved or if it is 'invalidated'
        // all property entries, that are not contained within the specified
        // collection of property names are removed from this NodeEntry.
        ItemState state = internalGetItemState();
        if (containsExtra && (state == null || state.getStatus() == Status.INVALIDATED)) {
            for (Iterator it = diff.iterator(); it.hasNext();) {
                QName propName = (QName) it.next();
                PropertyEntry pEntry = (PropertyEntry) properties.get(propName);
                if (pEntry != null) {
                    pEntry.remove();
                }
            }
        }
    }

    /**
     * @inheritDoc
     * @see NodeEntry#addNewPropertyEntry(QName, QPropertyDefinition)
     */
    public PropertyState addNewPropertyEntry(QName propName, QPropertyDefinition definition)
            throws ItemExistsException, RepositoryException {
        // check for an existing property
        PropertyEntry existing = (PropertyEntry) properties.get(propName);
        if (existing != null) {
            try {
                PropertyState existingState = existing.getPropertyState();
                int status = existingState.getStatus();

                if (Status.isTerminal(status)) {
                    // an old property-entry that is not valid any more
                    properties.remove(propName);
                } else if (status == Status.EXISTING_REMOVED) {
                    // transiently removed -> move it to the attic
                    propertiesInAttic.put(propName, existing);
                } else {
                    // existing is still existing -> cannot add same-named property
                    throw new ItemExistsException(propName.toString());
                }
            } catch (ItemNotFoundException e) {
                // entry does not exist on the persistent layer
                // -> therefore remove from properties map
                properties.remove(propName);
            } catch (RepositoryException e) {
                // some other error -> remove from properties map
                properties.remove(propName);
            }
        }

        // add the property entry
        PropertyEntry entry = PropertyEntryImpl.create(this, propName, factory);
        properties.put(propName, entry);

        PropertyState state = factory.getItemStateFactory().createNewPropertyState(entry, definition);
        ((PropertyEntryImpl) entry).internalSetItemState(state);

        return state;
    }

    /**
     * @param propName
     */
    PropertyEntry internalRemovePropertyEntry(QName propName) {
        PropertyEntry cpe = (PropertyEntry) properties.remove(propName);
        if (cpe == null) {
            cpe = (PropertyEntry) propertiesInAttic.remove(propName);
        }
        // special properties
        if (StateUtility.isUuidOrMixin(propName)) {
            notifyUUIDorMIXINRemoved(propName);
        }
        return cpe;
    }

    /**
     * @inheritDoc
     * @see NodeEntry#orderBefore(NodeEntry)
     */
    public void orderBefore(NodeEntry beforeEntry) throws RepositoryException {
        if (Status.NEW == getStatus()) {
            // new states get remove upon revert
            parent.childNodeEntries().reorder(this, beforeEntry);
        } else {
            createSiblingRevertInfos();
            parent.createRevertInfo();
            // now reorder child entries on parent
            NodeEntry previousBefore = parent.childNodeEntries().reorder(this, beforeEntry);
            parent.revertInfo.reordered(this, previousBefore);
        }
    }

   /**
    * @see NodeEntry#move(QName, NodeEntry, boolean)
    */
   public NodeEntry move(QName newName, NodeEntry newParent, boolean transientMove) throws RepositoryException {
       if (parent == null) {
           // the root may never be moved
           throw new RepositoryException("Root cannot be moved.");
       }

       // for existing nodeEntry that are 'moved' for the first time, the
       // original data must be stored and this entry is moved to the attic.
       if (transientMove && !isTransientlyMoved() && Status.NEW != getStatus()) {
           createSiblingRevertInfos();
           createRevertInfo();
           parent.childNodeAttic.add(this);
       }

       NodeEntryImpl entry = (NodeEntryImpl) parent.childNodeEntries().remove(this);
       if (entry != this) {
           // should never occur
           String msg = "Internal error. Attempt to move NodeEntry (" + getQName() + ") which is not connected to its parent.";
           log.error(msg);
           throw new RepositoryException(msg);
       }
       // set name and parent to new values
       parent = (NodeEntryImpl) newParent;
       name = newName;
       // register entry with its new parent
       parent.childNodeEntries().add(this);
       return this;
   }

    /**
     * @see NodeEntry#isTransientlyMoved()
     */
    public boolean isTransientlyMoved() {
        return revertInfo != null && revertInfo.isMoved();
    }

    /**
     * @param childEvent
     * @see NodeEntry#refresh(Event)
     */
    public void refresh(Event childEvent) {
        QName eventName = childEvent.getQPath().getNameElement().getName();
        switch (childEvent.getType()) {
            case Event.NODE_ADDED:
                int index = childEvent.getQPath().getNameElement().getNormalizedIndex();
                String uniqueChildID = null;
                if (childEvent.getItemId().getPath() == null) {
                    uniqueChildID = childEvent.getItemId().getUniqueID();
                }
                // first check if no matching child entry exists.
                // TODO: TOBEFIXED for SNSs
                if (childNodeEntries != null) {
                    NodeEntry cne;
                    if (uniqueChildID != null) {
                        cne = childNodeEntries.get(eventName, uniqueChildID);
                    } else {
                        cne = childNodeEntries.get(eventName, index);
                    }
                    if (cne == null) {
                        internalAddNodeEntry(eventName, uniqueChildID, index, childNodeEntries);
                    } else {
                        // child already exists -> deal with NEW entries, that were
                        // added by some other session.
                        // TODO: TOBEFIXED
                    }
                } // else: childNodeEntries not yet loaded -> ignore
                break;

            case Event.PROPERTY_ADDED:
                // create a new property reference if it has not been
                // added by some earlier 'add' event
                HierarchyEntry child = lookupEntry(childEvent.getItemId(), childEvent.getQPath());
                if (child == null) {
                    internalAddPropertyEntry(eventName);
                } else {
                    child.reload(false, true);
                }
                break;

            case Event.NODE_REMOVED:
            case Event.PROPERTY_REMOVED:
                child = lookupEntry(childEvent.getItemId(), childEvent.getQPath());
                if (child != null) {
                    child.remove();
                } // else: child-Entry has not been loaded yet -> ignore
                break;

            case Event.PROPERTY_CHANGED:
                child = lookupEntry(childEvent.getItemId(), childEvent.getQPath());
                if (child == null) {
                    // prop-Entry has not been loaded yet -> add propEntry
                    internalAddPropertyEntry(eventName);
                } else if (child.isAvailable()) {
                    // Reload data from server and try to merge them with the
                    // current session-state. if the latter is transiently
                    // modified and merge fails it must be marked STALE afterwards.
                    child.reload(false, false);
                    // special cases: jcr:uuid and jcr:mixinTypes affect the parent
                    // (i.e. this NodeEntry) since both props are protected
                    if (StateUtility.isUuidOrMixin(eventName)) {
                        notifyUUIDorMIXINModified((PropertyEntry) child);
                    }
                } // else: existing entry but state not yet built -> ignore event
                break;
            default:
                // ILLEGAL
                throw new IllegalArgumentException("Illegal event type " + childEvent.getType() + " for NodeState.");
        }
    }
    //-------------------------------------------------< HierarchyEntryImpl >---
    /**
     * @inheritDoc
     * @see HierarchyEntryImpl#doResolve()
     * <p/>
     * Returns a <code>NodeState</code>.
     */
    ItemState doResolve() throws ItemNotFoundException, RepositoryException {
        return factory.getItemStateFactory().createNodeState(getWorkspaceId(), this);
    }

    /**
     * @see HierarchyEntryImpl#buildPath(boolean)
     */
    Path buildPath(boolean wspPath) throws RepositoryException {
        // shortcut for root state
        if (parent == null) {
            return Path.ROOT;
        }
        // build path otherwise
        try {
            Path.PathBuilder builder = new Path.PathBuilder();
            buildPath(builder, this, wspPath);
            return builder.getPath();
        } catch (MalformedPathException e) {
            String msg = "Failed to build path of " + this;
            throw new RepositoryException(msg, e);
        }
    }

    /**
     * Adds the path element of an item id to the path currently being built.
     * On exit, <code>builder</code> contains the path of this entry.
     *
     * @param builder builder currently being used
     * @param hEntry HierarchyEntry of the state the path should be built for.
     */
    private static void buildPath(Path.PathBuilder builder, NodeEntryImpl nEntry, boolean wspPath) throws RepositoryException {
        NodeEntryImpl parentEntry = (wspPath && nEntry.revertInfo != null) ? nEntry.revertInfo.oldParent : nEntry.parent;
        // shortcut for root state
        if (parentEntry == null) {
            builder.addRoot();
            return;
        }

        // recursively build path of parent
        buildPath(builder, parentEntry, wspPath);

        int index = (wspPath) ? nEntry.getWorkspaceIndex() : nEntry.getIndex();
        QName name = (wspPath) ? nEntry.getWorkspaceQName() : nEntry.getQName();
        // add to path
        if (index == Path.INDEX_UNDEFINED) {
            throw new RepositoryException("Invalid index " + index + " with nodeEntry " + nEntry);
        }

        // TODO: check again. special treatment for index 0 for consistency with PathFormat.parse
        if (index == Path.INDEX_DEFAULT) {
            builder.addLast(name);
        } else {
            builder.addLast(name, index);
        }
    }

    //-----------------------------------------------< private || protected >---
    /**
     * @throws IllegalArgumentException if <code>this</code> is not the parent
     * of the given <code>ItemState</code>.
     */
    synchronized void revertPropertyRemoval(PropertyEntry propertyEntry) {
        if (propertyEntry.getParent() != this) {
            throw new IllegalArgumentException("Internal error: Parent mismatch.");
        }
        QName propName = propertyEntry.getQName();
        if (propertiesInAttic.containsKey(propName)) {
            properties.put(propName, propertiesInAttic.remove(propName));
        } // else: propEntry has never been moved to the attic (see 'addPropertyEntry')
    }

    /**
     *
     * @param oldName
     * @param oldIndex
     * @return
     */
    boolean matches(QName oldName, int oldIndex) {
        return getWorkspaceQName().equals(oldName) && getWorkspaceIndex() == oldIndex;
    }

    /**
     *
     * @param oldName
     * @return
     */
    boolean matches(QName oldName) {
        return getWorkspaceQName().equals(oldName);
    }


    private QName getWorkspaceQName() {
        if (revertInfo != null) {
            return revertInfo.oldName;
        } else {
            return getQName();
        }
    }

    private int getWorkspaceIndex() {
        if (revertInfo != null) {
            return revertInfo.oldIndex;
        } else {
            return getIndex();
        }
    }

    /**
     * Searches the child-entries of this NodeEntry for a matching child.
     * Since {@link #refresh(Event)} must always be called on the parent
     * NodeEntry, there is no need to check if a given event id would point
     * to this NodeEntry itself.
     *
     * @param eventId
     * @param eventPath
     * @return
     */
    private HierarchyEntry lookupEntry(ItemId eventId, Path eventPath) {
        QName childName = eventPath.getNameElement().getName();
        HierarchyEntry child = null;
        if (eventId.denotesNode()) {
            String uniqueChildID = (eventId.getPath() == null) ? eventId.getUniqueID() : null;
            // for external node-removal the attic must be consulted first
            // in order to be able to apply the changes to the proper node-entry.
            if (uniqueChildID != null) {
                child = childNodeAttic.get(uniqueChildID);
                if (child == null && childNodeEntries != null) {
                    child = childNodeEntries.get(childName, uniqueChildID);
                }
            }
            if (child == null) {
                int index = eventPath.getNameElement().getNormalizedIndex();
                child = lookupNodeEntry(childName, index);
            }
        } else {
            child = lookupPropertyEntry(childName);
        }
        if (child != null) {
            // a NEW hierarchyEntry may never be affected by an external
            // modification -> return null.
            ItemState state = ((HierarchyEntryImpl) child).internalGetItemState();
            if (state != null && state.getStatus() == Status.NEW) {
                return null;
            }
        }
        return child;
    }

    private NodeEntryImpl lookupNodeEntry(QName childName, int index) {
        NodeEntryImpl child = (NodeEntryImpl) childNodeAttic.get(childName, index);
        if (child == null && childNodeEntries != null) {
            List namedChildren = childNodeEntries.get(childName);
            for (Iterator it = namedChildren.iterator(); it.hasNext(); ) {
                NodeEntryImpl c = (NodeEntryImpl) it.next();
                if (c.matches(childName, index)) {
                    child = c;
                    break;
                }
            }
        }
        return child;
    }

    private PropertyEntry lookupPropertyEntry(QName childName) {
        // for external prop-removal the attic must be consulted first
        // in order not access a NEW prop shadowing a transiently removed
        // property with the same name.
        PropertyEntry child = (PropertyEntry) propertiesInAttic.get(childName);
        if (child == null) {
            child = (PropertyEntry) properties.get(childName);
        }
        return child;
    }

    /**
     * Deals with modified jcr:uuid and jcr:mixinTypes property.
     * See {@link #notifyUUIDorMIXINRemoved(QName)}
     *
     * @param child
     */
    private void notifyUUIDorMIXINModified(PropertyEntry child) {
        try {
            if (QName.JCR_UUID.equals(child.getQName())) {
                PropertyState ps = child.getPropertyState();
                setUniqueID(ps.getValue().getString());
            } else if (QName.JCR_MIXINTYPES.equals(child.getQName())) {
                NodeState state = (NodeState) internalGetItemState();
                if (state != null) {
                    PropertyState ps = child.getPropertyState();
                    state.setMixinTypeNames(StateUtility.getMixinNames(ps));
                } // nodestate not yet loaded -> ignore change
            }
        } catch (ItemNotFoundException e) {
            log.debug("Property with name " + child.getQName() + " does not exist (anymore)");
        } catch (RepositoryException e) {
            log.debug("Unable to access child property " + child.getQName(), e.getMessage());
        }
    }

    /**
     * Deals with removed jcr:uuid and jcr:mixinTypes property.
     * See {@link #notifyUUIDorMIXINModified(PropertyEntry)}
     *
     * @param propName
     */
    private void notifyUUIDorMIXINRemoved(QName propName) {
        if (QName.JCR_UUID.equals(propName)) {
            setUniqueID(null);
        } else if (QName.JCR_MIXINTYPES.equals(propName)) {
            NodeState state = (NodeState) internalGetItemState();
            if (state != null) {
                state.setMixinTypeNames(QName.EMPTY_ARRAY);
            }
        }
    }

    /**
     *
     * @return
     */
    private ChildNodeEntries childNodeEntries() throws InvalidItemStateException, RepositoryException {
        try {
            if (childNodeEntries == null) {
                childNodeEntries = new ChildNodeEntries(this);
                loadChildNodeEntries();
            } else if (childNodeEntries.getStatus() == ChildNodeEntries.STATUS_INVALIDATED) {
                reloadChildNodeEntries(childNodeEntries);
                childNodeEntries.setStatus(ChildNodeEntries.STATUS_OK);
            }
        } catch (ItemNotFoundException e) {
            log.debug("NodeEntry does not exist (anymore) -> remove.");
            remove();
            throw new InvalidItemStateException(e);
        }
        return childNodeEntries;
    }

    private void loadChildNodeEntries() throws ItemNotFoundException, RepositoryException {

        if (getStatus() == Status.NEW || Status.isTerminal(getStatus())) {
            return; // cannot retrieve child-entries from persistent layer
        }

        NodeId id = getWorkspaceId();
        Iterator it = factory.getItemStateFactory().getChildNodeInfos(id);
        // simply add all child entries to the empty collection
        while (it.hasNext()) {
            ChildInfo ci = (ChildInfo) it.next();
            internalAddNodeEntry(ci.getName(), ci.getUniqueID(), ci.getIndex(), childNodeEntries);
        }
    }

    private void reloadChildNodeEntries(ChildNodeEntries cnEntries) throws ItemNotFoundException, RepositoryException {
        if (getStatus() == Status.NEW || Status.isTerminal(getStatus())) {
            // nothing to do
            return;
        }
        NodeId id = getWorkspaceId();
        Iterator it = factory.getItemStateFactory().getChildNodeInfos(id);
        // create list from all ChildInfos (for multiple loop)
        List cInfos = new ArrayList();
        while (it.hasNext()) {
            cInfos.add((ChildInfo) it.next());
        }
        // first make sure the ordering of all existing entries is ok
        NodeEntry entry = null;
        for (it = cInfos.iterator(); it.hasNext();) {
            ChildInfo ci = (ChildInfo) it.next();
            NodeEntry nextEntry = cnEntries.get(ci);
            if (nextEntry != null) {
                if (entry != null) {
                    cnEntries.reorder(entry, nextEntry);
                }
                entry = nextEntry;
            }
        }
        // then insert the 'new' entries
        List newEntries = new ArrayList();
        for (it = cInfos.iterator(); it.hasNext();) {
            ChildInfo ci = (ChildInfo) it.next();
            NodeEntry beforeEntry = cnEntries.get(ci);
            if (beforeEntry == null) {
                NodeEntry ne = new NodeEntryImpl(this, ci.getName(), ci.getUniqueID(), factory);
                newEntries.add(ne);
            } else {
                // insert all new entries from the list BEFORE the existing
                // 'nextEntry'. Then clear the list.
                for (int i = 0; i < newEntries.size(); i++) {
                    cnEntries.add((NodeEntry) newEntries.get(i), beforeEntry);
                }
                newEntries.clear();
            }
        }
        // deal with new entries at the end
        for (int i = 0; i < newEntries.size(); i++) {
            cnEntries.add((NodeEntry) newEntries.get(i));
        }
    }

    /**
     * Returns an Iterator over all children entries, that currently are loaded
     * with this NodeEntry. NOTE, that if the childNodeEntries have not been
     * loaded yet, no attempt is made to do so.
     *
     * @param createNewList if true, both properties and childNodeEntries are
     * copied to new list, since recursive calls may call this node state to
     * inform the removal of a child entry.
     * @param includeAttic
     * @return
     */
    private Iterator getAllChildEntries(boolean createNewList, boolean includeAttic) {
        Iterator[] its;
        if (createNewList) {
            List props = new ArrayList(properties.values());
            List children = (childNodeEntries == null) ? Collections.EMPTY_LIST : new ArrayList(childNodeEntries);
            if (includeAttic) {
                List attic = new ArrayList(propertiesInAttic.values());
                its = new Iterator[] {attic.iterator(), props.iterator(), children.iterator()};
            } else {
                its = new Iterator[] {props.iterator(), children.iterator()};
            }
        } else {
            Iterator children = (childNodeEntries == null) ? Collections.EMPTY_LIST.iterator() : childNodeEntries.iterator();
            if (includeAttic) {
                its = new Iterator[] {propertiesInAttic.values().iterator(), properties.values().iterator(), children};
            } else {
                its = new Iterator[] {properties.values().iterator(), children};
            }
        }
        return new IteratorChain(its);
    }

    /**
     * Returns the index of the given <code>NodeEntry</code>.
     *
     * @param cne  the <code>NodeEntry</code> instance.
     * @return the index of the child node entry.
     * @throws ItemNotFoundException if the given entry isn't a valid child of
     * this <code>NodeEntry</code>.
     */
    private int getChildIndex(NodeEntry cne) throws ItemNotFoundException, RepositoryException {
        List sns = childNodeEntries().get(cne.getQName());
        // index is one based
        int index = Path.INDEX_DEFAULT;
        for (Iterator it = sns.iterator(); it.hasNext(); ) {
            NodeEntry entry = (NodeEntry) it.next();
            if (entry == cne) {
                return index;
            }
            // skip entries that belong to removed or invalid states.
            // NOTE, that in this case the nodestate must be available from the cne.
            if (EntryValidation.isValidNodeEntry(entry)) {
                index++;
            }
        }
        // not found (should not occur)
        throw new ItemNotFoundException("No valid child entry for NodeEntry " + cne);
    }

    /**
     * If 'revertInfo' is null it gets created from the current information
     * present on this entry.
     */
    private void createRevertInfo() {
        if (revertInfo == null) {
            revertInfo = new RevertInfo(parent, name, getIndex());
        }
    }

    /**
     * Special handling for MOVE and REORDER with same-name-siblings
     */
    private void createSiblingRevertInfos() throws RepositoryException {
        if (revertInfo != null) {
            return; // nothing to do
        }
        // for SNSs without UniqueID remember original index in order to
        // be able to build the workspaceID TODO: improve
        List sns = parent.childNodeEntries().get(name);
        if (sns.size() > 1) {
            for (Iterator it = sns.iterator(); it.hasNext();) {
                NodeEntryImpl sibling = (NodeEntryImpl) it.next();
                if (sibling.getUniqueID() == null && Status.NEW != sibling.getStatus()) {
                    sibling.createRevertInfo();
                }
            }
        }
    }

    /**
     * Revert a transient move and reordering of child entries
     */
    private void revertTransientChanges() throws RepositoryException {
        if (revertInfo == null) {
            return; // nothing to do
        }

        if (isTransientlyMoved())  {
            // move NodeEntry back to its original parent
            // TODO improve for simple renaming
            parent.childNodeEntries().remove(this);
            revertInfo.oldParent.childNodeAttic.remove(this);

            // now restore moved entry with the old name and index and re-add
            // it to its original parent (unless it got destroyed)
            parent = revertInfo.oldParent;
            name = revertInfo.oldName;
            ItemState state = internalGetItemState();
            if (state != null && !Status.isTerminal(state.getStatus())) {
                parent.childNodeEntries().add(this, revertInfo.oldIndex);
            }
        }
        // revert reordering of child-node-entries
        revertInfo.revertReordering();

        revertInfo.dispose();
        revertInfo = null;
    }

    /**
     * This entry has be set to 'EXISTING' again -> move and/or reordering of
     * child entries has been completed and the 'revertInfo' needs to be
     * reset/removed.
     */
    private void completeTransientChanges() {
        // old parent can forget this one
        revertInfo.oldParent.childNodeAttic.remove(this);
        revertInfo.dispose();
        revertInfo = null;
    }

    //--------------------------------------------------------< inner class >---
    /**
     * Upon move of this entry or upon reorder of its child-entries store
     * original hierarchy information for later revert and in order to be able
     * to build the workspace id(s).
     */
    private class RevertInfo implements ItemStateLifeCycleListener {

        private NodeEntryImpl oldParent;
        private QName oldName;
        private int oldIndex;

        private Map reorderedChildren;

        private RevertInfo(NodeEntryImpl oldParent, QName oldName, int oldIndex) {
            this.oldParent = oldParent;
            this.oldName = oldName;
            this.oldIndex = oldIndex;

            ItemState state = internalGetItemState();
            if (state != null) {
                state.addListener(this);
            } // else: should never be null.
        }

        private void dispose() {
            ItemState state = internalGetItemState();
            if (state != null) {
                state.removeListener(this);
            }

            if (reorderedChildren != null) {
                // special handling of SNS-children  TODO: improve
                // since reordered sns-children are not marked modified (unless they
                // got modified by some other action, their revertInfo
                // must be disposed manually
                for (Iterator it = reorderedChildren.keySet().iterator(); it.hasNext();) {
                    NodeEntry ne = (NodeEntry) it.next();
                    List sns = childNodeEntries.get(ne.getQName());
                    if (sns.size() > 1) {
                        for (Iterator snsIt = sns.iterator(); snsIt.hasNext();) {
                            NodeEntryImpl sibling = (NodeEntryImpl) snsIt.next();
                            if (sibling.revertInfo != null && Status.EXISTING == sibling.getStatus()) {
                                sibling.revertInfo.dispose();
                                sibling.revertInfo = null;
                            }
                        }
                    }
                }
                reorderedChildren.clear();
            }
        }

        private boolean isMoved() {
            return oldParent != getParent() || !getQName().equals(oldName);
        }

        private void reordered(NodeEntry insertEntry, NodeEntry previousBefore) {
            if (reorderedChildren == null) {
                reorderedChildren = new LinkedHashMap();
            }
            reorderedChildren.put(insertEntry, previousBefore);
        }

        private void revertReordering() {
            if (reorderedChildren == null) {
                return; // nothing to do
            }
            // revert all 'reorder' calls in in reverse other they were performed
            NodeEntry[] reordered = (NodeEntry[]) reorderedChildren.keySet().toArray(new NodeEntry[reorderedChildren.size()]);
            for (int i = reordered.length-1; i >= 0; i--) {
                NodeEntry ordered = reordered[i];
                if (isValidReorderedChild(ordered)) {
                    NodeEntry previousBefore = (NodeEntry) reorderedChildren.get(ordered);
                    if (previousBefore == null || isValidReorderedChild(previousBefore)) {
                        childNodeEntries.reorder(ordered, previousBefore);
                    }
                }
            }
        }

        private boolean isValidReorderedChild(NodeEntry child) {
            if (Status.isTerminal(child.getStatus())) {
                log.warn("Cannot revert reordering. 'previousBefore' does not exist any more.");
                return false;
            }
            if (child.isTransientlyMoved()) {
                // child has been moved away -> move back
                try {
                    child.revert();
                } catch (RepositoryException e) {
                    log.error("Internal error", e);
                    return false;
                }
            }
            return true;
        }

        /**
         * @see ItemStateLifeCycleListener#statusChanged(ItemState, int)
         */
        public void statusChanged(ItemState state, int previousStatus) {
            switch (state.getStatus()) {
                case Status.EXISTING:
                    // stop listening
                    state.removeListener(this);
                    completeTransientChanges();
                    break;

                case Status.REMOVED:
                case Status.STALE_DESTROYED:
                    // stop listening
                    state.removeListener(this);
                    // remove from the attic
                    try {
                        revertTransientChanges();
                    } catch (RepositoryException e) {
                        log.warn("Internal error", e);
                    }
                    break;
            }
        }
    }
}
