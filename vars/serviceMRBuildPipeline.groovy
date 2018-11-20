def call(body) {
    delete("sws","1.4.1")
}

def delete(String artifact, String version) {
    def connection = new URL("https://nexus.tools.tools178.k8syard.com/service/siesta/rest/beta/search?name=${artifact}&version=${version}").openConnection()
    connection.setRequestMethod("GET")
    connection.setRequestProperty("Authorization", "Basic ZGRhZG1pbjpBbHRlcm5hdGl2ZWx5Tm9uc2Vuc2U=")
    println(connection.getInputStream().getText())
}
