/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), available at http://sourceforge.net/projects/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Gunter Zeilinger, Huetteldorferstr. 24/10, 1150 Vienna/Austria/Europe.
 * Portions created by the Initial Developer are Copyright (C) 2005
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * Gunter Zeilinger <gunterze@gmail.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4che2.hp.spi;

import java.util.ArrayList;
import java.util.Iterator;

import javax.imageio.spi.ServiceRegistry;

import org.dcm4che2.hp.plugins.AlongAxisComparatorSpi;
import org.dcm4che2.hp.plugins.ByAcqTimeComparatorSpi;
import org.dcm4che2.hp.plugins.ImagePlaneSelectorSpi;

/**
 * @author gunter zeilinger(gunterze@gmail.com)
 * @version $Reversion$ $Date: 2007-11-23 11:07:58 +0100 (Fr, 23 Nov 2007) $
 * @since Oct 17, 2005
 */
public class HPRegistry extends ServiceRegistry
{

    private static final HPRegistry registry = new HPRegistry();

    private HPRegistry()
    {
        super(getInitialCategories());
        registerStandardSpis();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        registerApplicationClasspathSpis(cl);
    }

    private static Iterator<Class<?>> getInitialCategories()
    {
        ArrayList<Class<?>> l = new ArrayList<Class<?>>(2);
        l.add(HPSelectorSpi.class);
        l.add(HPComparatorSpi.class);
        return l.iterator();
    }

    public static HPRegistry getHPRegistry()
    {
        return registry ;
    }

    private void registerStandardSpis()
    {
        // Hardwire standard SPIs
        registerServiceProvider(new ImagePlaneSelectorSpi());
        registerServiceProvider(new AlongAxisComparatorSpi());
        registerServiceProvider(new ByAcqTimeComparatorSpi());
    }

    public void registerApplicationClasspathSpis(ClassLoader cl)
    {
        Iterator categories = getCategories();
        while (categories.hasNext())
        {
            Class c = (Class) categories.next();
            Iterator riter = Service.providers(c, cl);
            while (riter.hasNext())
            {
                registerServiceProvider(riter.next());
            }
        }
    }

}
