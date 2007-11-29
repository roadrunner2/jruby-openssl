/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006, 2007 Ola Bini <ola@ologix.com>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.ext.openssl;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERString;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.CallbackFactory;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * @author <a href="mailto:ola.bini@ki.se">Ola Bini</a>
 */
public class Request extends RubyObject {
    private static ObjectAllocator REQUEST_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new Request(runtime, klass);
        }
    };
    
    public static void createRequest(Ruby runtime, RubyModule mX509) {
        RubyClass cRequest = mX509.defineClassUnder("Request",runtime.getObject(),REQUEST_ALLOCATOR);
        RubyClass openSSLError = runtime.getModule("OpenSSL").getClass("OpenSSLError");
        mX509.defineClassUnder("RequestError",openSSLError,openSSLError.getAllocator());

        CallbackFactory reqcb = runtime.callbackFactory(Request.class);

        cRequest.defineMethod("initialize",reqcb.getOptMethod("_initialize"));
        cRequest.defineFastMethod("initialize_copy",reqcb.getFastMethod("initialize_copy",IRubyObject.class));
        cRequest.defineFastMethod("to_pem",reqcb.getFastMethod("to_pem"));
        cRequest.defineFastMethod("to_der",reqcb.getFastMethod("to_der"));
        cRequest.defineFastMethod("to_s",reqcb.getFastMethod("to_pem"));
        cRequest.defineFastMethod("to_text",reqcb.getFastMethod("to_text"));
        cRequest.defineFastMethod("version",reqcb.getFastMethod("version"));
        cRequest.defineFastMethod("version=",reqcb.getFastMethod("set_version",IRubyObject.class));
        cRequest.defineFastMethod("subject",reqcb.getFastMethod("subject"));
        cRequest.defineFastMethod("subject=",reqcb.getFastMethod("set_subject",IRubyObject.class));
        cRequest.defineFastMethod("signature_algorithm",reqcb.getFastMethod("signature_algorithm"));
        cRequest.defineFastMethod("public_key",reqcb.getFastMethod("public_key"));
        cRequest.defineFastMethod("public_key=",reqcb.getFastMethod("set_public_key",IRubyObject.class));
        cRequest.defineFastMethod("sign",reqcb.getFastMethod("sign",IRubyObject.class,IRubyObject.class));
        cRequest.defineFastMethod("verify",reqcb.getFastMethod("verify",IRubyObject.class));
        cRequest.defineFastMethod("attributes",reqcb.getFastMethod("attributes"));
        cRequest.defineFastMethod("attributes=",reqcb.getFastMethod("set_attributes",IRubyObject.class));
        cRequest.defineFastMethod("add_attribute",reqcb.getFastMethod("add_attribute",IRubyObject.class));
    }

    private IRubyObject version;
    private IRubyObject subject;
    private IRubyObject public_key;
    private boolean valid = false;

    private List attrs;

    private PKCS10CertificationRequestExt req;

    public Request(Ruby runtime, RubyClass type) {
        super(runtime,type);
        attrs = new ArrayList();
    }

    public IRubyObject _initialize(IRubyObject[] args, Block block) throws Exception {
        if(org.jruby.runtime.Arity.checkArgumentCount(getRuntime(),args,0,1) == 0) {
            return this;
        }
        req = new PKCS10CertificationRequestExt(args[0].convertToString().getBytes());
        version = getRuntime().newFixnum(req.getVersion());
        String algo = req.getPublicKey().getAlgorithm();;
        byte[] enc = req.getPublicKey().getEncoded();
        ThreadContext tc = getRuntime().getCurrentContext();
        if("RSA".equalsIgnoreCase(algo)) {
            this.public_key = ((RubyModule)(getRuntime().getModule("OpenSSL").getConstant("PKey"))).getClass("RSA").callMethod(tc,"new",RubyString.newString(getRuntime(), enc));
        } else if("DSA".equalsIgnoreCase(algo)) {
            this.public_key = ((RubyModule)(getRuntime().getModule("OpenSSL").getConstant("PKey"))).getClass("DSA").callMethod(tc,"new",RubyString.newString(getRuntime(), enc));
        } else {
            throw getRuntime().newLoadError("not implemented algo for public key: " + algo);
        }
        org.bouncycastle.asn1.x509.X509Name subName = req.getCertificationRequestInfo().getSubject();
        subject = ((RubyModule)getRuntime().getModule("OpenSSL").getConstant("X509")).getClass("Name").callMethod(tc,"new");
        DERSequence subNameD = (DERSequence)subName.toASN1Object();
        for(int i=0;i<subNameD.size();i++) {
            DERSequence internal = (DERSequence)((DERSet)subNameD.getObjectAt(i)).getObjectAt(0);
            Object oid = internal.getObjectAt(0);
            Object v = null;
            if(internal.getObjectAt(1) instanceof DERString) {
                v = ((DERString)internal.getObjectAt(1)).getString();
            }
            Object t = getRuntime().newFixnum(ASN1.idForClass(internal.getObjectAt(1).getClass()));
            ((X509Name)subject).addEntry(oid,v,t);
        }
        ASN1Set in_attrs = req.getCertificationRequestInfo().getAttributes();
        for(Enumeration enm = in_attrs.getObjects();enm.hasMoreElements();) {
            DERSet obj = (DERSet)enm.nextElement();
            for(Enumeration enm2 = obj.getObjects();enm2.hasMoreElements();) {
                DERSequence val = (DERSequence)enm2.nextElement();
                DERObjectIdentifier v0 = (DERObjectIdentifier)val.getObjectAt(0);
                DERObject v1 = (DERObject)val.getObjectAt(1);
                IRubyObject a1 = getRuntime().newString(((String)(ASN1.getSymLookup(getRuntime()).get(v0))));
                IRubyObject a2 = ASN1.decode(getRuntime().getModule("OpenSSL").getConstant("ASN1"),RubyString.newString(getRuntime(), v1.getDEREncoded()));
                add_attribute(((RubyModule)(getRuntime().getModule("OpenSSL").getConstant("X509"))).getConstant("Attribute").callMethod(tc,"new",new IRubyObject[]{a1,a2}));
            }
        }
        this.valid = true;
        return this;
    }

    public IRubyObject initialize_copy(IRubyObject obj) {
        System.err.println("WARNING: unimplemented method called: init_copy");
        if(this == obj) {
            return this;
        }
        checkFrozen();
        version = getRuntime().getNil();
        subject = getRuntime().getNil();
        public_key = getRuntime().getNil();
        return this;
    }

    public IRubyObject to_pem() {
        System.err.println("WARNING: unimplemented method called: to_pem");
        return getRuntime().getNil();
    }

    public IRubyObject to_der() throws Exception {
        return RubyString.newString(getRuntime(), req.getDEREncoded());
    }

    public IRubyObject to_text() {
        System.err.println("WARNING: unimplemented method called: to_text");
        return getRuntime().getNil();
    }

    public IRubyObject version() {
        return this.version;
    }

    public IRubyObject set_version(IRubyObject val) {
        if(val != version) {
            valid = false;
        }
        this.version = val;
        if(!val.isNil() && req != null) {
            req.setVersion(RubyNumeric.fix2int(val));
        }
        return val;
    }

    public IRubyObject subject() {
        return this.subject;
    }

    public IRubyObject set_subject(IRubyObject val) {
        if(val != subject) {
            valid = false;
        }
        this.subject = val;
        return val;
    }

    public IRubyObject signature_algorithm() {
        System.err.println("WARNING: unimplemented method called: signature_algorithm");
        return getRuntime().getNil();
    }

    public IRubyObject public_key() {
        return this.public_key;
    }

    public IRubyObject set_public_key(IRubyObject val) {
        if(val != public_key) {
            valid = false;
        }
        this.public_key = val;
        return val;
    }

    public IRubyObject sign(IRubyObject key, IRubyObject digest) throws Exception {
        String keyAlg = ((PKey)public_key).getAlgorithm();
        String digAlg = ((Digest)digest).getAlgorithm();
        
        if(("DSA".equalsIgnoreCase(keyAlg) && "MD5".equalsIgnoreCase(digAlg)) || 
           ("RSA".equalsIgnoreCase(keyAlg) && "DSS1".equals(((Digest)digest).name().toString())) ||
           ("DSA".equalsIgnoreCase(keyAlg) && "SHA1".equals(((Digest)digest).name().toString()))) {
            throw new RaiseException(getRuntime(), (RubyClass)(((RubyModule)(getRuntime().getModule("OpenSSL").getConstant("X509"))).getConstant("RequestError")), null, true);
        }

        ASN1EncodableVector v1 = new ASN1EncodableVector();
        for(Iterator iter = attrs.iterator();iter.hasNext();) {
            v1.add(((Attribute)iter.next()).toASN1());
        }
        req = new PKCS10CertificationRequestExt(digAlg + "WITH" + keyAlg,((X509Name)this.subject).getRealName(),((PKey)public_key).getPublicKey(),new DERSet(v1),((PKey)key).getPrivateKey());
        req.setVersion(RubyNumeric.fix2int(version));
        valid = true;
        return this;
    }

    public IRubyObject verify(IRubyObject key) {
        try {
            return valid && req.verify(((PKey)(key.callMethod(getRuntime().getCurrentContext(),"public_key"))).getPublicKey()) ? getRuntime().getTrue() : getRuntime().getFalse();
        } catch(Exception e) {
            return getRuntime().getFalse();
        }
    }

    public IRubyObject attributes() {
        return getRuntime().newArray(attrs);
    }

    public IRubyObject set_attributes(IRubyObject val) throws Exception {
        valid = false;
        attrs.clear();
        attrs.addAll(((RubyArray)val).getList());
        if(req != null) {
            ASN1EncodableVector v1 = new ASN1EncodableVector();
            for(Iterator iter = attrs.iterator();iter.hasNext();) {
                v1.add(((Attribute)iter.next()).toASN1());
            }
            req.setAttributes(new DERSet(v1));
        }
        return val;
    }

    public IRubyObject add_attribute(IRubyObject val) throws Exception {
        valid = false;
        attrs.add(val);
        if(req != null) {
            ASN1EncodableVector v1 = new ASN1EncodableVector();
            for(Iterator iter = attrs.iterator();iter.hasNext();) {
                v1.add(((Attribute)iter.next()).toASN1());
            }
            req.setAttributes(new DERSet(v1));
        }
        return getRuntime().getNil();
    }
}// Request