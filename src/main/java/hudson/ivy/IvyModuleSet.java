/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Jorg Heymans, Peter Hayes, Red Hat, Inc., Stephen Connolly, id:cactusman
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.ivy;

import static hudson.Util.fixEmpty;
import static hudson.model.ItemGroupMixIn.loadChildren;
import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildableItemWithBuildWrappers;
import hudson.model.DependencyGraph;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.ResourceActivity;
import hudson.model.SCMedItem;
import hudson.model.Saveable;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Queue.Task;
import hudson.search.CollectionSearchIndex;
import hudson.search.SearchIndexBuilder;
import hudson.tasks.Ant;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrappers;
import hudson.tasks.Publisher;
import hudson.tasks.Ant.AntInstallation;
import hudson.util.CopyOnWriteMap;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.Function1;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;

/**
 * Group of {@link IvyModule}s.
 *
 * <p>
 * This corresponds to the group of Ivy module descriptors that constitute a single
 * branch of projects.
 *
 * @author Timothy Bingaman
 */
public final class IvyModuleSet extends AbstractIvyProject<IvyModuleSet,IvyModuleSetBuild> implements TopLevelItem, ItemGroup<IvyModule>, SCMedItem, Saveable, BuildableItemWithBuildWrappers {
    /**
     * All {@link IvyModule}s, keyed by their {@link IvyModule#getModuleName()} module name}s.
     */
    transient /*final*/ Map<ModuleName,IvyModule> modules = new CopyOnWriteMap.Tree<ModuleName,IvyModule>();

    /**
     * Topologically sorted list of modules. This only includes live modules,
     * since archived ones usually don't have consistent history.
     */
    @CopyOnWrite
    transient List<IvyModule> sortedActiveModules;

    private String ivyFilePattern;

    private String ivyFileExcludesPattern;

    private String targets;

    private String ivyBranch;

    private String relativePathToDescriptorFromModuleRoot;

    /**
     * Identifies {@link AntInstallation} to be used.
     */
    private String antName;

    /**
     * ANT_OPTS if not null.
     */
    private String antOpts;

    /**
     * Optional build script path relative to the workspace.
     * Used for the Ant '-f' option.
     */
    private String buildFile;

    /**
     * Optional properties to be passed to Ant. Follows {@link Properties} syntax.
     */
    private String antProperties;

    /**
     * If true, the build will be aggregator style, meaning all the modules are
     * executed in a single Ant invocation, as in CLI. False otherwise, meaning
     * each module is built separately and possibly in parallel.
     */
    private boolean aggregatorStyleBuild = true;

    /**
     * If true, and if aggregatorStyleBuild is false, the build will check the
     * changeset before building, and if there are changes, only those modules
     * which have changes or those modules which failed or were unstable in the
     * previous build will be built directly. Any modules depending on the
     * directly built modules will also be built.
     */
    private boolean incrementalBuild = false;

    /**
     * The name of the property used to pass the names of the changed modules
     * to the build when both incremental build and aggregated build options are
     * selected.
     */
    private String changedModulesProperty;

    /**
     * If true, do not automatically schedule a build when one of the project
     * dependencies is built.
     */
    private boolean ignoreUpstreamChanges = false;

    /**
     * If true, do not archive artifacts to the master.
     */
    private boolean archivingDisabled = false;

    /**
     * List of active {@link Publisher}s configured for this project.
     */
    private DescribableList<Publisher,Descriptor<Publisher>> publishers =
        new DescribableList<Publisher,Descriptor<Publisher>>(this);

    /**
     * List of active {@link BuildWrapper}s configured for this project.
     */
    private DescribableList<BuildWrapper,Descriptor<BuildWrapper>> buildWrappers =
        new DescribableList<BuildWrapper, Descriptor<BuildWrapper>>(this);

    public IvyModuleSet(String name) {
        super(Hudson.getInstance(),name);
    }

    public String getUrlChildPrefix() {
        // seemingly redundant "./" is used to make sure that ':' is not interpreted as the scheme identifier
        return ".";
    }

    @Override
    public Hudson getParent() {
        return Hudson.getInstance();
    }

    public Collection<IvyModule> getItems() {
        return modules.values();
    }

    @Exported
    public Collection<IvyModule> getModules() {
        return getItems();
    }

    public IvyModule getItem(String name) {
        return modules.get(ModuleName.fromString(name));
    }

    public IvyModule getModule(String name) {
        return getItem(name);
    }

    @Override
    protected void updateTransientActions() {
        super.updateTransientActions();
        for (IvyModule module: modules.values()) {
            module.updateTransientActions();
        }
        if(publishers!=null)    // this method can be loaded from within the onLoad method, where this might be null
            for (BuildStep step : publishers) {
                Collection<? extends Action> a = step.getProjectActions(this);
                if(a!=null)
                    transientActions.addAll(a);
            }

        if (buildWrappers!=null)
	        for (BuildWrapper step : buildWrappers) {
	            Collection<? extends Action> a = step.getProjectActions(this);
	            if(a!=null)
	                transientActions.addAll(a);
	        }
    }

    @Override
    protected void addTransientActionsFromBuild(IvyModuleSetBuild build, Set<Class> added) {
        if(build==null)    return;

        for (Action a : build.getActions())
            if(a instanceof IvyAggregatedReport)
                if(added.add(a.getClass()))
                    transientActions.add(((IvyAggregatedReport)a).getProjectAction(this));

        List<IvyReporter> list = build.projectActionReporters;
        if(list==null)   return;

        for (IvyReporter step : list) {
            if(!added.add(step.getClass()))     continue;   // already added
            Action a = step.getAggregatedProjectAction(this);
            if(a!=null)
                transientActions.add(a);
        }
    }

    /**
     * Called by {@link IvyModule#doDoDelete(StaplerRequest, StaplerResponse)}.
     * Real deletion is done by the caller, and this method only adjusts the
     * data structure the parent maintains.
     */
    /*package*/ void onModuleDeleted(IvyModule module) {
        modules.remove(module.getModuleName());
    }

    /**
     * Returns true if there's any disabled module.
     */
    public boolean hasDisabledModule() {
        for (IvyModule m : modules.values()) {
            if(m.isDisabled())
                return true;
        }
        return false;
    }

    /**
     * Possibly empty list of all disabled modules (if disabled==true)
     * or all enabeld modules (if disabled==false)
     */
    public List<IvyModule> getDisabledModules(boolean disabled) {
        if(!disabled && sortedActiveModules!=null)
            return sortedActiveModules;

        List<IvyModule> r = new ArrayList<IvyModule>();
        for (IvyModule m : modules.values()) {
            if(m.isDisabled()==disabled)
                r.add(m);
        }
        return r;
    }

    public boolean isIncrementalBuild() {
        return incrementalBuild;
    }

    public String getChangedModulesProperty() {
        return changedModulesProperty;
    }

    public boolean isAggregatorStyleBuild() {
        return aggregatorStyleBuild;
    }

    public boolean ignoreUpstreamChanges() {
        return ignoreUpstreamChanges;
    }

    public boolean isArchivingDisabled() {
        return archivingDisabled;
    }

    public void setIncrementalBuild(boolean incrementalBuild) {
        this.incrementalBuild = incrementalBuild;
    }

    public String getIvyFilePattern() {
        return ivyFilePattern;
    }

    public void setIvyFilePattern(String ivyFilePattern) {
        this.ivyFilePattern = ivyFilePattern;
    }

    public String getIvyFileExcludesPattern() {
        return ivyFileExcludesPattern;
    }

    public void setIvyFileExcludesPattern(String ivyFileExcludesPattern) {
        this.ivyFileExcludesPattern = ivyFileExcludesPattern;
    }

    public String getIvyBranch() {
        return ivyBranch;
    }

    public void setIvyBranch(String ivyBranch) {
        this.ivyBranch = ivyBranch;
    }

    public void setAggregatorStyleBuild(boolean aggregatorStyleBuild) {
        this.aggregatorStyleBuild = aggregatorStyleBuild;
    }

    public void setIgnoreUpstremChanges(boolean ignoreUpstremChanges) {
        this.ignoreUpstreamChanges = ignoreUpstremChanges;
    }

    public void setIsArchivingDisabled(boolean archivingDisabled) {
        this.archivingDisabled = archivingDisabled;
    }

    /**
     * List of active {@link Publisher}s that should be applied to all module builds.
     */
    public DescribableList<Publisher, Descriptor<Publisher>> getModulePublishers() {
        return aggregatorStyleBuild ? new DescribableList<Publisher, Descriptor<Publisher>>(this) : publishers;
    }

    /**
     * List of active {@link Publisher}s. Can be empty but never null.
     */
    public DescribableList<Publisher, Descriptor<Publisher>> getPublishers() {
        return publishers;
    }

    @Override
    public DescribableList<Publisher, Descriptor<Publisher>> getPublishersList() {
        return publishers;
    }

    public DescribableList<BuildWrapper, Descriptor<BuildWrapper>> getBuildWrappersList() {
        return buildWrappers;
    }

    /**
     * List of active {@link BuildWrapper}s. Can be empty but never null.
     *
     * @deprecated as of 1.335
     *      Use {@link #getBuildWrappersList()} to be consistent with other subtypes of {@link AbstractProject}.
     */
    @Deprecated
    public DescribableList<BuildWrapper, Descriptor<BuildWrapper>> getBuildWrappers() {
        return buildWrappers;
    }

    @Override
    public Object getDynamic(String token, StaplerRequest req, StaplerResponse rsp) {
        if (ModuleName.isValid(token))
            return getModule(token);
        return super.getDynamic(token,req,rsp);
    }

    public File getRootDirFor(IvyModule child) {
        return new File(getModulesDir(),child.getModuleName().toFileSystemName());
    }

    @Override
    public Collection<Job> getAllJobs() {
        Set<Job> jobs = new HashSet<Job>(getItems());
        jobs.add(this);
        return jobs;
    }

    @Override
    protected Class<IvyModuleSetBuild> getBuildClass() {
        return IvyModuleSetBuild.class;
    }

    @Override
    protected SearchIndexBuilder makeSearchIndex() {
        return super.makeSearchIndex()
            .add(new CollectionSearchIndex<IvyModule>() {// for computers
                @Override
                protected IvyModule get(String key) {
                    for (IvyModule m : modules.values()) {
                        if(m.getDisplayName().equals(key))
                            return m;
                    }
                    return null;
                }
                @Override
                protected Collection<IvyModule> all() {
                    return modules.values();
                }
                @Override
                protected String getName(IvyModule o) {
                    return o.getName();
                }
            });
    }

    @Override
    public boolean isFingerprintConfigured() {
        return true;
    }

    @Override
    public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
        modules = Collections.emptyMap(); // needed during load
        super.onLoad(parent, name);

        modules = loadChildren(this, getModulesDir(),new Function1<ModuleName,IvyModule>() {
            public ModuleName call(IvyModule module) {
                return module.getModuleName();
            }
        });
        if(publishers==null)
            publishers = new DescribableList<Publisher,Descriptor<Publisher>>(this);
        publishers.setOwner(this);
        if(buildWrappers==null)
            buildWrappers = new DescribableList<BuildWrapper, Descriptor<BuildWrapper>>(this);
        buildWrappers.setOwner(this);

        updateTransientActions();
    }

    private File getModulesDir() {
        return new File(getRootDir(),"modules");
    }

    /**
     * To make it easy to grasp relationship among modules
     * and the module set, we'll align the build numbers of
     * all the modules.
     *
     * <p>
     * This method is invoked from {@link Executor#run()},
     * and because of the mutual exclusion among {@link IvyModuleSetBuild}
     * and {@link IvyBuild}, we can safely touch all the modules.
     */
    @Override
    public synchronized int assignBuildNumber() throws IOException {
        // determine the next value
        updateNextBuildNumber();

        return super.assignBuildNumber();
    }

    @Override
    public void logRotate() throws IOException, InterruptedException {
        super.logRotate();
        // perform the log rotation of modules
        for (IvyModule m : modules.values())
            m.logRotate();
    }

    /**
     * The next build of {@link IvyModuleSet} must have
     * the build number newer than any of the current module build.
     */
    /*package*/ void updateNextBuildNumber() throws IOException {
        int next = this.nextBuildNumber;
        for (IvyModule m : modules.values())
            next = Math.max(next,m.getNextBuildNumber());

        if(this.nextBuildNumber!=next) {
            this.nextBuildNumber=next;
            this.saveNextBuildNumber();
        }
    }

    @Override
    protected void buildDependencyGraph(DependencyGraph graph) {
        publishers.buildDependencyGraph(this,graph);
        buildWrappers.buildDependencyGraph(this,graph);
    }

    public AntInstallation inferAntInstallation() {
        return getAnt();
    }

    @Override
    protected Set<ResourceActivity> getResourceActivities() {
        final Set<ResourceActivity> activities = new HashSet<ResourceActivity>();

        activities.addAll(super.getResourceActivities());
        activities.addAll(Util.filter(publishers,ResourceActivity.class));
        activities.addAll(Util.filter(buildWrappers,ResourceActivity.class));

        return activities;
    }

    public AbstractProject<?,?> asProject() {
        return this;
    }

    /**
     * Gets the list of targets to execute.
     */
    public String getTargets() {
        return targets;
    }

    public void setTargets(String targets) {
        this.targets = targets;
    }

    public String getRelativePathToDescriptorFromModuleRoot() {
        return relativePathToDescriptorFromModuleRoot;
    }

    public void setRelativePathToDescriptorFromModuleRoot(String relativePathToDescriptorFromModuleRoot) {
        this.relativePathToDescriptorFromModuleRoot = relativePathToDescriptorFromModuleRoot;
    }

    /**
     * Possibly null, whitespace-separated (including TAB, NL, etc) VM options
     * to be used to launch Ant process.
     *
     * If antOpts is null or empty, we'll return the globally-defined ANT_OPTS.
     */
    public String getAntOpts() {
        if ((antOpts!=null) && (antOpts.trim().length()>0)) {
            return antOpts;
        }
        else {
            return DESCRIPTOR.getGlobalAntOpts();
        }
    }

    /**
     * Set ANT_OPTS.
     */
    public void setAntOpts(String antOpts) {
        this.antOpts = antOpts;
    }

    public String getBuildFile() {
        return buildFile;
    }

    public String getAntProperties() {
        return antProperties;
    }

    /**
     * Gets the Ant to invoke.
     * If null, we pick any random Ant installation.
     */
    public AntInstallation getAnt() {
        for( AntInstallation i : DESCRIPTOR.getAntDescriptor().getInstallations() ) {
            if(antName==null || i.getName().equals(antName))
                return i;
        }
        return null;
    }

    public void setAnt(String antName) {
        this.antName = antName;
    }

    /**
     * Returns the {@link IvyModule}s that are in the queue.
     */
    public List<Queue.Item> getQueueItems() {
        List<Queue.Item> r = new ArrayList<hudson.model.Queue.Item>();
        for( Queue.Item item : Hudson.getInstance().getQueue().getItems() ) {
            Task t = item.task;
            if((t instanceof IvyModule && ((IvyModule)t).getParent()==this) || t ==this)
                r.add(item);
        }
        return r;
    }

    /**
     * Gets the list of targets specified by the user,
     * without taking inheritance into account.
     *
     * <p>
     * This is only used to present the UI screen, and in
     * all the other cases {@link #getTargets()} should be used.
     */
    public String getUserConfiguredTargets() {
        return targets;
    }

//
//
// Web methods
//
//

    @Override
    protected void submit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, FormException {
        super.submit(req,rsp);
        JSONObject json = req.getSubmittedForm();

        ignoreUpstreamChanges = !json.has("triggerByDependency");
        ivyFilePattern = Util.fixEmptyAndTrim(json.getString("ivyFilePattern"));
        ivyFileExcludesPattern = Util.fixEmptyAndTrim(json.getString("ivyFileExcludesPattern"));
        targets = Util.fixEmptyAndTrim(json.getString("targets"));
        ivyBranch = Util.fixEmptyAndTrim(json.getString("ivyBranch"));
        relativePathToDescriptorFromModuleRoot = Util.fixEmptyAndTrim(json.getString("relativePathToDescriptorFromModuleRoot"));
        antName = Util.fixEmptyAndTrim(json.getString("antName"));
        buildFile = Util.fixEmptyAndTrim(json.getString("buildFile"));
        antOpts = Util.fixEmptyAndTrim(json.getString("antOpts"));
        antProperties = Util.fixEmptyAndTrim(json.getString("antProperties"));
        aggregatorStyleBuild = !req.hasParameter("perModuleBuild");
        incrementalBuild = req.hasParameter("incrementalBuild");
        if (incrementalBuild)
            changedModulesProperty = Util.fixEmptyAndTrim(json.getJSONObject("incrementalBuild").getString("changedModulesProperty"));

        publishers.rebuild(req,json,BuildStepDescriptor.filter(Publisher.all(),this.getClass()));
        buildWrappers.rebuild(req,json,BuildWrappers.getFor(this));

        updateTransientActions(); // to pick up transient actions from builder, publisher, etc.
    }

    public Class<? extends AbstractProject> getModuleClass() {
        return IvyModule.class;
    }

    /**
     * Delete all disabled modules.
     */
    public void doDoDeleteAllDisabledModules(StaplerResponse rsp) throws IOException, InterruptedException {
        checkPermission(DELETE);
        for( IvyModule m : getDisabledModules(true))
            m.delete();
        rsp.sendRedirect2(".");
    }

    /**
     * Check the location of the ivy descriptor file, alternate settings file, etc - any file.
     */
    public FormValidation doCheckFileInWorkspace(@QueryParameter String value) throws IOException, ServletException {
        IvyModuleSetBuild lb = getLastBuild();
        if (lb!=null) {
            FilePath ws = lb.getModuleRoot();
            if(ws!=null)
                return ws.validateRelativePath(value,true,true);
        }
        return FormValidation.ok();
    }

    /**
     * Check that the provided file is a relative path. And check that it exists, just in case.
     */
    public FormValidation doCheckFileRelative(@QueryParameter String value) throws IOException, ServletException {
        String v = fixEmpty(value);
        if ((v == null) || (v.length() == 0)) {
            // Null values are allowed.
            return FormValidation.ok();
        }
        if ((v.startsWith("/")) || (v.startsWith("\\")) || (v.matches("^\\w\\:\\\\.*"))) {
            return FormValidation.error("Alternate settings file must be a relative path.");
        }

        IvyModuleSetBuild lb = getLastBuild();
        if (lb!=null) {
            FilePath ws = lb.getModuleRoot();
            if(ws!=null)
                return ws.validateRelativePath(value,true,true);
        }
        return FormValidation.ok();
    }

    public TopLevelItemDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension(ordinal=900)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends AbstractProjectDescriptor {
        /**
         * Globally-defined ANT_OPTS.
         */
        private String globalAntOpts;

        public String getGlobalAntOpts() {
            return globalAntOpts;
        }

        public void setGlobalAntOpts(String globalAntOpts) {
            this.globalAntOpts = globalAntOpts;
            save();
        }

        @Override
        public String getDisplayName() {
            return Messages.IvyModuleSet_DiplayName();
        }

        @Override
        public IvyModuleSet newInstance(String name) {
            return new IvyModuleSet(name);
        }

        public Ant.DescriptorImpl getAntDescriptor() {
            return Hudson.getInstance().getDescriptorByType(Ant.DescriptorImpl.class);
        }
    }
}
