/*
 * Copyright  2003-2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.tools.ant.types;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.Hashtable;
import java.util.Properties;
import java.util.Stack;
import java.util.Vector;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.PropertyHelper;
import org.apache.tools.ant.util.FileNameMapper;
import org.apache.tools.ant.util.regexp.RegexpMatcher;
import org.apache.tools.ant.util.regexp.RegexpMatcherFactory;

/**
 * A set of properties.
 *
 * @since Ant 1.6
 */
public class PropertySet extends DataType {

    private boolean dynamic = true;
    private boolean negate = false;
    private Set cachedNames;
    private Vector ptyRefs = new Vector();
    private Vector setRefs = new Vector();
    private Mapper _mapper;

    public static class PropertyRef {

        private int count;
        private String name;
        private String regex;
        private String prefix;
        private String builtin;

        public void setName(String name) {
            assertValid("name", name);
            this.name = name;
        }

        public void setRegex(String regex) {
            assertValid("regex", regex);
            this.regex = regex;
        }

        public void setPrefix(String prefix) {
            assertValid("prefix", prefix);
            this.prefix = prefix;
        }

        public void setBuiltin(BuiltinPropertySetName b) {
            String builtin = b.getValue();
            assertValid("builtin", builtin);
            this.builtin = builtin;
        }

        private void assertValid(String attr, String value) {
            if (value == null || value.length() < 1) {
                throw new BuildException("Invalid attribute: " + attr);
            }

            if (++count != 1) {
                throw new BuildException("Attributes name, regex, and "
                    + "prefix are mutually exclusive");
            }
        }

        public String toString() {
            return "name=" + name + ", regex=" + regex + ", prefix=" + prefix
                + ", builtin=" + builtin;
        }

    }

    public void appendName(String name) {
        PropertyRef ref = new PropertyRef();
        ref.setName(name);
        addPropertyref(ref);
    }

    public void appendRegex(String regex) {
        PropertyRef ref = new PropertyRef();
        ref.setRegex(regex);
        addPropertyref(ref);
    }

    public void appendPrefix(String prefix) {
        PropertyRef ref = new PropertyRef();
        ref.setPrefix(prefix);
        addPropertyref(ref);
    }

    public void appendBuiltin(BuiltinPropertySetName b) {
        PropertyRef ref = new PropertyRef();
        ref.setBuiltin(b);
        addPropertyref(ref);
    }

    public void setMapper(String type, String from, String to) {
        Mapper mapper = createMapper();
        Mapper.MapperType mapperType = new Mapper.MapperType();
        mapperType.setValue(type);
        mapper.setFrom(from);
        mapper.setTo(to);
    }

    public void addPropertyref(PropertyRef ref) {
        assertNotReference();
        ptyRefs.addElement(ref);
    }

    public void addPropertyset(PropertySet ref) {
        assertNotReference();
        setRefs.addElement(ref);
    }

    public Mapper createMapper() {
        assertNotReference();
        if (_mapper != null) {
            throw new BuildException("Too many <mapper>s!");
        }
        _mapper = new Mapper(getProject());
        return _mapper;
    }

    /**
     * A nested filenamemapper
     * @param fileNameMapper the mapper to add
     * @since Ant 1.6.3
     */
    public void add(FileNameMapper fileNameMapper) {
        createMapper().add(fileNameMapper);
    }

    public void setDynamic(boolean dynamic) {
        assertNotReference();
        this.dynamic = dynamic;
    }

    public void setNegate(boolean negate) {
        assertNotReference();
        this.negate = negate;
    }

    public boolean getDynamic() {
        return isReference() ? getRef().dynamic : dynamic;
    }

    public Mapper getMapper() {
        return isReference() ? getRef()._mapper : _mapper;
    }

    /**
     * Convert the system properties to a hashtable.
     * Use propertynames to get the list of properties (including
     * default ones).
     */
    private Hashtable getAllSystemProperties() {
        Hashtable ret = new Hashtable();
        for (Enumeration e = System.getProperties().propertyNames();
             e.hasMoreElements();) {
            Object o = e.nextElement();
            if (o instanceof String) {
                String name = (String) o;
                ret.put(name, System.getProperties().getProperty(name));
            }
        }
        return ret;
    }

    /**
     * this is the operation to get the existing or recalculated properties.
     * @return
     */
    public Properties getProperties() {
        Set names = null;
        Project prj = getProject();
        Hashtable props =
            prj == null ? getAllSystemProperties() : prj.getProperties();

        if (getDynamic() || cachedNames == null) {
            names = new HashSet();
            if (isReference()) {
                getRef().addPropertyNames(names, props);
            } else {
                addPropertyNames(names, props);
            }
            // Add this PropertySet's nested PropertySets' property names.
            for (Enumeration e = setRefs.elements(); e.hasMoreElements();) {
                PropertySet set = (PropertySet) e.nextElement();
                names.addAll(set.getProperties().keySet());
            }
            if (negate) {
                //make a copy...
                HashSet complement = new HashSet(props.keySet());
                complement.removeAll(names);
                names = complement;
            }
            if (!getDynamic()) {
                cachedNames = names;
            }
        } else {
            names = cachedNames;
        }

        FileNameMapper mapper = null;
        Mapper myMapper = getMapper();
        if (myMapper != null) {
            mapper = myMapper.getImplementation();
        }
        Properties properties = new Properties();
        //iterate through the names, get the matching values
        for (Iterator iter = names.iterator(); iter.hasNext();) {
            String name = (String) iter.next();
            String value = (String) props.get(name);
            if (mapper != null) {
                String[] newname = mapper.mapFileName(name);
                if (newname != null) {
                    name = newname[0];
                }
            }
            properties.setProperty(name, value);
        }
        return properties;
    }

    /**
     * @param  names the output Set to fill with the property names
     *         matching this PropertySet selection criteria.
     * @param  properties the current Project properties, passed in to
     *         avoid needless duplication of the Hashtable during recursion.
     */
    private void addPropertyNames(Set names, Hashtable properties) {
        Project prj = getProject();

        // Add this PropertySet's property names.
        for (Enumeration e = ptyRefs.elements(); e.hasMoreElements();) {
            PropertyRef ref = (PropertyRef) e.nextElement();
            if (ref.name != null) {
                if (prj != null && prj.getProperty(ref.name) != null) {
                    names.add(ref.name);
                }
            } else if (ref.prefix != null) {
                for (Enumeration p = properties.keys(); p.hasMoreElements();) {
                    String name = (String) p.nextElement();
                    if (name.startsWith(ref.prefix)) {
                        names.add(name);
                    }
                }
            } else if (ref.regex != null) {
                RegexpMatcherFactory matchMaker = new RegexpMatcherFactory();
                RegexpMatcher matcher = matchMaker.newRegexpMatcher();
                matcher.setPattern(ref.regex);
                for (Enumeration p = properties.keys(); p.hasMoreElements();) {
                    String name = (String) p.nextElement();
                    if (matcher.matches(name)) {
                        names.add(name);
                    }
                }
            } else if (ref.builtin != null) {

                if (ref.builtin.equals(BuiltinPropertySetName.ALL)) {
                    names.addAll(properties.keySet());
                } else if (ref.builtin.equals(BuiltinPropertySetName.SYSTEM)) {
                    names.addAll(System.getProperties().keySet());
                } else if (ref.builtin.equals(BuiltinPropertySetName
                                              .COMMANDLINE)) {
                    names.addAll(getProject().getUserProperties().keySet());
                } else {
                    throw new BuildException("Impossible: Invalid builtin "
                                             + "attribute!");
                }
            } else {
                throw new BuildException("Impossible: Invalid PropertyRef!");
            }
        }
    }

    /**
     * Performs the check for circular references and returns the
     * referenced FileList.
     */
    protected PropertySet getRef() {
        if (!isChecked()) {
            Stack stk = new Stack();
            stk.push(this);
            dieOnCircularReference(stk, getProject());
        }

        Object o = getRefid().getReferencedObject(getProject());
        if (!(o instanceof PropertySet)) {
            String msg = getRefid().getRefId()
                + " doesn\'t denote a propertyset";
            throw new BuildException(msg);
        } else {
            return (PropertySet) o;
        }
    }

    /**
     * Sets the value of the refid attribute.
     *
     * @param  r the reference this datatype should point to.
     * @throws BuildException if another attribute was set, since
     *         refid and all other attributes are mutually exclusive.
     */
    public final void setRefid(Reference r) {
        if (!noAttributeSet) {
            throw tooManyAttributes();
        }
        super.setRefid(r);
    }

    /**
     * Ensures this data type is not a reference.
     *
     * <p>Calling this method as the first line of every bean method of
     * this data type (setXyz, addXyz, createXyz) ensure proper handling
     * of the refid attribute.</p>
     *
     * @throws BuildException if the refid attribute was already set, since
     *         refid and all other attributes are mutually exclusive.
     */
    protected final void assertNotReference() {
        if (isReference()) {
            throw tooManyAttributes();
        }
        noAttributeSet = false;
    }
    private boolean noAttributeSet = true;

    /**
     * Used for propertyref's builtin attribute.
     */
    public static class BuiltinPropertySetName extends EnumeratedAttribute {
        static final String ALL = "all";
        static final String SYSTEM = "system";
        static final String COMMANDLINE = "commandline";
        public String[] getValues() {
            return new String[] {ALL, SYSTEM, COMMANDLINE};
        }
    }
} // END class PropertySet

