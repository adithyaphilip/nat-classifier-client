javac -target 1.6 -source 1.6 *.java && 
jar cmf MANIFEST.MF archive.jar *.class &&
#keytool -genkey -keystore myKeyStore -alias me
#keytool -selfcert -keystore myKeyStore -alias me
jarsigner -keystore appletCerts archive.jar me
