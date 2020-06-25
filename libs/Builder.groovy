#!/usr/bin/groovy
package com.opspresso;

def debug() {
    sh """
        ls -al
        ls -al ~
    """
}

def prepare(name = "sample") {
    // image name
    echo "# name: ${name}"
    this.name = name

    this.cluster = ""
    this.namespace = ""

    this.base_domain = ""
    this.sub_domain = ""

    // this.slack_token = ""

    this.archiva = ""
    this.chartmuseum = ""
    this.harbor = ""
    this.jenkins = ""
    this.nexus = ""
    this.registry = ""
    this.sonarqube = ""

    this.values_home = ""

    // local cluster
    load_variables()
}

def scan(source_lang = "") {
    this.source_lang = source_lang
    this.source_root = "."

    // language
    if (!source_lang || source_lang == "java") {
        scan_langusge("pom.xml", "java")
    }
    if (!source_lang || source_lang == "nodejs") {
        scan_langusge("package.json", "nodejs")
    }

    echo "# source_lang: ${this.source_lang}"
    echo "# source_root: ${this.source_root}"

    // version
    set_version()
}

def load_variables() {
    def path = "./data.groovy"

    // groovy variables
    sh """
        kubectl get cm jenkins-env -n default -o json | jq -r .data.groovy > ${path}
        cat ${path} | grep def
    """

    if (!fileExists("${path}")) {
        echo "load_variables:no file ${path}"
        throw new RuntimeException("no file ${path}")
    }

    def val = load "${path}"

    this.base_domain = val.base_domain
    // this.slack_token = val.slack_token

    if (val.role == "devops") {
        this.archiva = val.archiva
        this.chartmuseum = val.chartmuseum
        this.harbor = val.harbor
        this.jenkins = val.jenkins
        this.nexus = val.nexus
        this.registry = val.registry
        this.sonarqube = val.sonarqube
    }
}

def scan_langusge(target = "", target_lang = "") {
    def target_path = sh(script: "find . -name ${target} | head -1", returnStdout: true).trim()

    if (target_path) {
        def target_root = sh(script: "dirname ${target_path}", returnStdout: true).trim()

        if (target_root) {
            this.source_lang = target_lang
            this.source_root = target_root

            // maven mirror
            if (target_lang == "java") {
                if (this.archiva) {
                    def settings = "/root/.m2/settings.xml"

                    // if (fileExists("${settings}")) {
                    def m2_home = "${home}/.m2"

                    def mirror_of  = "*"
                    def mirror_url = "https://${archiva}/repository/internal/"
                    def mirror_xml = "<mirror><id>mirror</id><url>${mirror_url}</url><mirrorOf>${mirror_of}</mirrorOf></mirror>"

                    sh """
                        mkdir -p ${m2_home}
                        cp -f ${settings} ${m2_home}/settings.xml
                        sed -i -e \"s|<!-- ### configured mirrors ### -->|${mirror_xml}|\" ${m2_home}/settings.xml
                    """
                    // }
                }
                // if (this.nexus) {
                //     def settings = "/root/.m2/settings.xml"

                //     // if (fileExists("${settings}")) {
                //     def m2_home = "${home}/.m2"

                //     def mirror_of  = "*,!nexus-public,!nexus-releases,!nexus-snapshots"
                //     def mirror_url = "https://${nexus}/repository/maven-public/"
                //     def mirror_xml = "<mirror><id>mirror</id><url>${mirror_url}</url><mirrorOf>${mirror_of}</mirrorOf></mirror>"

                //     sh """
                //         mkdir -p ${m2_home}
                //         cp -f ${settings} ${m2_home}/settings.xml
                //         sed -i -e \"s|<!-- ### configured mirrors ### -->|${mirror_xml}|\" ${m2_home}/settings.xml
                //     """
                //     // }
                // }
            }
        }
    }
}

def set_version(appVersion = "", version = "") {
    // version
    if (!appVersion) {
        if (fileExists("./VERSION")) {
            appVersion = sh(script: "cat ./VERSION | sed 's/x/0/'", returnStdout: true).trim()
        } else {
            appVersion = "v0.0.0"
        }
    }

    def dt = (new Date()).format('yyyyMMdd-HHmm')

    version = "${appVersion}-${dt}"

    echo "# version: ${version}"

    this.appVersion = appVersion
    this.version = version
}

def env_cluster(cluster = "") {
    if (!cluster) {
        echo "env_cluster:cluster is null."
        // throw new RuntimeException("cluster is null.")
        return
    }

    if (cluster == "local") {
        return
    }

    this.cluster = cluster

    // check cluster secret
    count = sh(script: "kubectl get secret -n devops | grep 'kube-config-${cluster}' | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        echo "env_cluster:cluster is null."
        throw new RuntimeException("cluster is null.")
    }

    sh """
        mkdir -p /root/.aws /root/.kube
        kubectl get secret kube-config-${cluster} -n devops -o json | jq -r .data.aws | base64 -d > /root/.aws/config
        kubectl get secret kube-config-${cluster} -n devops -o json | jq -r .data.text | base64 -d > /root/.kube/config
    """

    sh "kubectl config current-context"

    // check current context
    count = sh(script: "kubectl config current-context | grep '${cluster}' | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        echo "env_cluster:current-context is not match."
        throw new RuntimeException("current-context is not match.")
    }

    // // target cluster
    // load_variables()
}

def env_namespace(namespace = "") {
    if (!namespace) {
        echo "env_namespace:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    this.namespace = namespace

    // check namespace
    count = sh(script: "kubectl get ns ${namespace} 2>&1 | grep Active | grep ${namespace} | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        sh "kubectl create namespace ${namespace}"
    }
}

def env_config(type = "", name = "", namespace = "") {
    if (!type) {
        echo "env_config:type is null."
        throw new RuntimeException("type is null.")
    }
    if (!name) {
        echo "env_config:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!namespace) {
        echo "env_config:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    // check config
    count = sh(script: "kubectl get ${type} -n ${namespace} | grep ${name} | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        return "false"
    }

    return "true"

    // // md5sum
    // sum = sh(script: "kubectl get ${type} -n ${namespace} ${name} -o yaml | md5sum | awk '{printf \$1}'", returnStdout: true).trim()
    // return sum
}

def make_chart(path = "", latest = false) {
    if (!name) {
        echo "make_chart:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "make_chart:version is null."
        throw new RuntimeException("version is null.")
    }
    if (!path) {
        path = "charts/${name}"
    }
    if (latest) {
        echo "latest version scan"
        version = scan_images_version(name, true)
    }

    if (!fileExists("${path}")) {
        echo "no file ${path}"
        return
    }

    dir("${path}") {
        sh """
            sed -i -e \"s/name: .*/name: ${name}/\" Chart.yaml
            sed -i -e \"s/appVersion: .*/appVersion: ${appVersion}/\" Chart.yaml
            sed -i -e \"s/version: .*/version: ${version}/\" Chart.yaml
            sed -i -e \"s/tag: .*/tag: ${version}/g\" values.yaml
        """

        if (registry) {
            sh "sed -i -e \"s|repository: .*|repository: ${registry}/${name}|\" values.yaml"
        }
    }
}

def build_chart(path = "") {
    if (!name) {
        echo "build_chart:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "build_chart:version is null."
        throw new RuntimeException("version is null.")
    }
    if (!path) {
        path = "charts/${name}"
    }

    // helm init
    helm_init()

    // helm plugin
    helm_plugin()

    // make chart
    make_chart(path)

    // helm dependency build
    if (fileExists("${path}/requirements.yaml")) {
        dir("charts") {
            sh """
                helm dependency build ${name}
            """
        }
    }

    // helm push
    dir("${path}") {
        sh """
            cat Chart.yaml
            helm lint .
        """

        if (chartmuseum) {
            sh "helm push . chartmuseum"
        }

        // if (harbor) {
        //     // TODO username, password
        //     sh "helm push --username admin --password password . harbor"
        // }
    }

    sh """
        helm repo update
        helm search repo ${name}
    """
}

def build_image() {
    if (!name) {
        echo "build_image:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "build_image:version is null."
        throw new RuntimeException("version is null.")
    }

    sh "docker build -t ${name}:${version} ."

    if (registry) {
        sh """
            docker tag ${name}:${version} ${registry}/${name}:${version}
            docker push ${registry}/${name}:${version}
        """
    }

    // if (harbor) {
    //     sh """
    //         echo 'password' | docker login ${harbor} -u admin --password-stdin
    //         docker tag ${name}:${version} ${harbor}/library/${name}:${version}
    //         docker push ${harbor}/library/${name}:${version}
    //     """
    // }
}

def helm_init() {
    sh """
        helm version
    """

    if (chartmuseum) {
        sh "helm repo add chartmuseum https://${chartmuseum}"
    }

    // if (harbor) {
    //     sh "helm repo add harbor https://${harbor}/chartrepo/library"
    // }

    sh """
        helm repo list
        helm repo update
    """
}

def helm_plugin() {
    // helm plugin
    count = sh(script: "helm plugin list | grep 'Push chart package' | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        sh """
            helm plugin install https://github.com/chartmuseum/helm-push
            helm plugin list
        """
    }
}

def apply(cluster = "", namespace = "", type = "", yaml = "") {
    if (!name) {
        echo "apply:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "apply:version is null."
        throw new RuntimeException("version is null.")
    }
    if (!cluster) {
        echo "apply:cluster is null."
        // throw new RuntimeException("cluster is null.")
    }
    if (!namespace) {
        echo "apply:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    if (!type) {
        type = "secret"
    }
    if (!yaml) {
        if (!cluster) {
            yaml = "${type}/${namespace}/${name}.yaml"
        } else {
            yaml = "${type}/${cluster}/${namespace}/${name}.yaml"
        }
    }

    // yaml
    yaml_path = sh(script: "find . -name ${name}.yaml | grep '${yaml}' | head -1", returnStdout: true).trim()
    if (!yaml_path) {
        echo "apply:yaml_path is null."
        throw new RuntimeException("yaml_path is null.")
    }

    sh """
        sed -i -e \"s|name: REPLACE-ME|name: ${name}|\" ${yaml_path}
    """

    // cluster
    env_cluster(cluster)

    // namespace
    env_namespace(namespace)

    sh """
        kubectl apply -n ${namespace} -f ${yaml_path}
    """
}

def deploy(cluster = "", namespace = "", sub_domain = "", profile = "", values_path = "") {
    if (!name) {
        echo "deploy:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "deploy:version is null."
        throw new RuntimeException("version is null.")
    }
    if (!cluster) {
        echo "deploy:cluster is null."
        // throw new RuntimeException("cluster is null.")
    }
    if (!namespace) {
        echo "deploy:namespace is null."
        throw new RuntimeException("namespace is null.")
    }
    if (!sub_domain) {
        sub_domain = "${name}-${namespace}"
    }
    if (!profile) {
        profile = namespace
    }

    // env cluster
    env_cluster(cluster)

    // env namespace
    env_namespace(namespace)

    // helm init
    helm_init()

    this.sub_domain = sub_domain

    // config configmap
    // configmap = env_config("configmap", name, namespace)
    // configmap_enabled = "false"
    // if (configmap_hash == "") {
    //     configmap_enabled = "true"
    // }

    // config secret
    // secret = env_config("secret", name, namespace)
    // secret_enabled = "false"
    // if (secret_hash != "") {
    //     secret_enabled = "true"
    // }

    // extra_values (format = --set KEY=VALUE)
    extra_values = ""

    // latest version
    if (version == "latest") {
        version = sh(script: "helm search repo chartmuseum/${name} | grep ${name} | head -1 | awk '{print \$2}'", returnStdout: true).trim()
        if (version == "") {
            echo "deploy:latest version is null."
            throw new RuntimeException("latest version is null.")
        }
    }

    // Keep latest pod count
    desired = sh(script: "kubectl get deploy -n ${namespace} | grep ${name} | head -1 | awk '{print \$4}'", returnStdout: true).trim()
    if (desired != "" && desired != "0") {
        extra_values = "--set replicaCount=${desired}"
    }

    // // values_path
    // if (!values_path) {
    //     values_path = ""
    //     if (values_home) {
    //         count = sh(script: "ls ${values_home}/${name} | grep '${namespace}.yaml' | wc -l", returnStdout: true).trim()
    //         if ("${count}" == "0") {
    //             echo "deploy:values_path not found."
    //             throw new RuntimeException("values_path not found.")
    //         } else {
    //             values_path = "${values_home}/${name}/${namespace}.yaml"
    //         }
    //     }
    // }

    // app-version: https://github.com/helm/helm/pull/5492
    // --app-version ${version}

    if (values_path) {

        // helm install
        sh """
            helm upgrade --install ${name}-${namespace} chartmuseum/${name} \
                --version ${version} \
                --namespace ${namespace} --devel \
                --values ${values_path} \
                --set namespace=${namespace} \
                --set profile=${profile} \
                ${extra_values}
        """
        // --set configmap.enabled=${configmap} \
        // --set secret.enabled=${secret} \

    } else {

        // helm install
        sh """
            helm upgrade --install ${name}-${namespace} chartmuseum/${name} \
                --version ${version} \
                --namespace ${namespace} --devel \
                --set ingress.subdomain=${sub_domain} \
                --set ingress.basedomain=${base_domain} \
                --set namespace=${namespace} \
                --set profile=${profile} \
                ${extra_values}
        """
        // --set configmap.enabled=${configmap} \
        // --set secret.enabled=${secret} \

    }

    sh """
        helm list -n ${namespace}
        helm history ${name}-${namespace} -n ${namespace} --max 10
    """

    // print ingress host
    host = sh(script: "kubectl get ing -n ${namespace} | grep ${name} | head -1 | awk '{print \$2}'", returnStdout: true).trim()
    if (host != "") {
        echo "http://${host}"
    }
}

def scan_helm(cluster = "", namespace = "") {
    // must have cluster
    if (!cluster) {
        echo "scan_helm:cluster is null."
        // throw new RuntimeException("cluster is null.")
    }

    env_cluster(cluster)

    // admin can scan all images,
    // others can scan own images.
    if (!namespace) {
        list = sh(script: "helm ls | awk '{print \$1}'", returnStdout: true).trim()
    } else {
        list = sh(script: "helm ls --namespace ${namespace} | awk '{print \$1}'", returnStdout: true).trim()
    }
    list
}

def scan_images() {
    // if (!chartmuseum) {
    //     load_variables()
    // }
    list = sh(script: "curl -X GET https://${registry}/v2/_catalog | jq -r '.repositories[]'", returnStdout: true).trim()
    list
}

def scan_images_version(image_name = "", latest = false) {
    // if (!chartmuseum) {
    //     load_variables()
    // }
    if (latest) {
      list = sh(script: "curl -X GET https://${registry}/v2/${image_name}/tags/list | jq -r '.tags[]' | sort -r | head -n 1", returnStdout: true).trim()
    } else {
      list = sh(script: "curl -X GET https://${registry}/v2/${image_name}/tags/list | jq -r '.tags[]' | sort -r", returnStdout: true).trim()
    }
    list
}

def scan_charts() {
    // if (!chartmuseum) {
    //     load_variables()
    // }
    list = sh(script: "curl https://${chartmuseum}/api/charts | jq -r 'keys[]'", returnStdout: true).trim()
    list
}

def scan_charts_version(mychart = "", latest = false) {
    // if (!chartmuseum) {
    //     load_variables()
    // }
    if (latest) {
      list = sh(script: "curl https://${chartmuseum}/api/charts/${mychart} | jq -r '.[].version' | sort -r | head -n 1", returnStdout: true).trim()
    } else {
      list = sh(script: "curl https://${chartmuseum}/api/charts/${mychart} | jq -r '.[].version' | sort -r", returnStdout: true).trim()
    }
    list
}

def rollback(cluster = "", namespace = "", revision = "") {
    if (!name) {
        echo "rollback:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!cluster) {
        echo "rollback:cluster is null."
        // throw new RuntimeException("cluster is null.")
    }
    if (!namespace) {
        echo "rollback:namespace is null."
        throw new RuntimeException("namespace is null.")
    }
    if (!revision) {
        revision = "0"
    }

    // env cluster
    env_cluster(cluster)

    // helm init
    helm_init()

    sh """
        helm search repo chartmuseum/${name}
        helm history ${name}-${namespace} --max 10
    """

    sh "helm rollback ${name}-${namespace} ${revision}"
}

def remove(cluster = "", namespace = "") {
    if (!name) {
        echo "remove:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!cluster) {
        echo "remove:cluster is null."
        // throw new RuntimeException("cluster is null.")
    }
    if (!namespace) {
        echo "remove:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    // env cluster
    env_cluster(cluster)

    // helm init
    helm_init()

    sh """
        helm search repo ${name}
        helm history ${name}-${namespace} --max 10
    """

    sh "helm delete --purge ${name}-${namespace}"
}

def get_source_root(source_root = "") {
    if (!source_root) {
        if (!this.source_root) {
            source_root = "."
        } else {
            source_root = this.source_root
        }
    }
    return source_root
}

def get_m2_settings() {
    if (this.nexus) {
        settings = "/home/jenkins/.m2/settings.xml"
        if (fileExists("${settings}")) {
            return "-s ${settings}"
        }
    }
    return ""
}

def npm_build(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        sh "npm run build"
    }
}

def npm_test(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        sh "npm run test"
    }
}

def npm_sonar(source_root = "" ) {
    if (!this.sonarqube) {
        echo "npm_sonar:sonarqube is null."
        throw new RuntimeException("sonarqube is null.")
    }
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        sh "npm install -g sonarqube-scanner"
        sh "sonar-scanner -Dsonar.host.url=https://${this.sonarqube}"
    }
}

def mvn_build(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        settings = get_m2_settings()
        sh "mvn package ${settings} -DskipTests=true"
    }
}

def mvn_test(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        settings = get_m2_settings()
        sh "mvn test ${settings}"
    }
}

def mvn_sonar(source_root = "" ) {
    if (!this.sonarqube) {
        echo "mvn_sonar:sonarqube is null."
        throw new RuntimeException("sonarqube is null.")
    }
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        settings = get_m2_settings()
        sh "mvn sonar:sonar ${settings} -Dsonar.host.url=https://${this.sonarqube} -DskipTests=true"
    }
}

def failure(token = "", type = "") {
    if (!name) {
        echo "failure:name is null."
        throw new RuntimeException("name is null.")
    }
    // slack(token, "danger", "${type} Failure", "`${name}` `${version}`", "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}>")
}

def success(token = "", type = "") {
    if (!name) {
        echo "success:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "success:version is null."
        throw new RuntimeException("version is null.")
    }
    def title = "${type} Success"
    def message = ""
    def footer = ""
    if (sub_domain) {
        if (cluster) {
            message = "`${name}` `${version}` :satellite: `${namespace}` :earth_asia: `${cluster}`"
        } else {
            message = "`${name}` `${version}` :satellite: `${namespace}`"
        }
        footer = "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}> : <https://${sub_domain}.${base_domain}|${name}-${namespace}>"
    } else {
        message = "`${name}` `${version}` :heavy_check_mark:"
        footer = "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}>"
    }
    // slack(token, "good", title, message, "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}>")
}

def proceed(token = "", type = "", namespace = "") {
    if (!name) {
        echo "proceed:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "proceed:version is null."
        throw new RuntimeException("version is null.")
    }
    // slack(token, "warning", "${type} Proceed?", "`${name}` `${version}` :rocket: `${namespace}`", "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}>")
}

// def slack(token = "", color = "", title = "", message = "", footer = "") {
//     try {
//         if (slack_token) {
//             if (!token) {
//                 token = slack_token
//             } else if (token instanceof List) {
//                 token.add(slack_token)
//             } else {
//                 token = [token, slack_token]
//             }
//         }
//         if (token) {
//             if (token instanceof List) {
//                 for (item in token) {
//                     send(item, color, title, message, footer)
//                 }
//             } else {
//                 send(token, color, title, message, footer)
//             }
//         }
//     } catch (ignored) {
//     }
// }

// def send(token = "", color = "", title = "", message = "", footer = "") {
//     try {
//         if (token && token != "") {
//             sh """
//                 curl -sL opspresso.github.io/tools/slack.sh | bash -s -- --token=\'${token}\' \
//                 --footer=\'$footer\' --footer_icon='https://jenkins.io/sites/default/files/jenkins_favicon.ico' \
//                 --color=\'${color}\' --title=\'${title}\' \'${message}\'
//             """
//         }
//     } catch (ignored) {
//     }
// }

return this
