package udp;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class Cliente implements Protocolo.Avisos {

    private final TelaCliente tela;
    private final InetSocketAddress servidor; //endereco do sv
    private final DatagramSocket socket; //socket principal do cliente

    public Cliente(TelaCliente tela, String ip) throws IOException {
        this.tela = tela;
        this.servidor = new InetSocketAddress(ip, Protocolo.PORTA);
        this.socket = new DatagramSocket();
    }

    //conecta ao sv, se registra, pega lista de arquivos, e fica esperando as notificacoes de atualizzacao da lista de arquivos
    public void conectar(String id) throws IOException {
        Protocolo.enviarEsperar(socket, bytes("REGISTER|" + id), servidor, "ACK|REGISTER", this);
        DatagramPacket lista = Protocolo.enviarEsperar(socket, bytes("LIST"), servidor, "LIST", this);
        tela.atualizarLista(Protocolo.texto(lista));

        socket.setSoTimeout(0);
        Thread escuta = new Thread(this::escutar);
        escuta.setDaemon(true);
        escuta.start();
    }

    //fica esperando o UPDATE_LIST e qnd chega atualiza a tela
    private void escutar() {
        while (true) {
            try {
                DatagramPacket pacote = new DatagramPacket(new byte[Protocolo.TAM_BUFFER], Protocolo.TAM_BUFFER);
                socket.receive(pacote);
                String texto = Protocolo.texto(pacote);
                if (texto.startsWith("UPDATE_LIST")) {
                    tela.atualizarLista(texto);
                    tela.mensagem("lista de arquivos atualizada pelo servidor");
                }
            } catch (IOException e) {
                tela.mensagem("erro ao escutar o servidor: " + e.getMessage());
                return;
            }
        }
    }

    //avisa o sv q vai enviar, envia o arquivo dps de separar em partes
    public void upload(File arquivo) throws IOException {
        byte[] dados = Files.readAllBytes(arquivo.toPath());
        byte[][] partes = Protocolo.fragmentar(dados);

        try (DatagramSocket socketUpload = new DatagramSocket()) {
            String pedido = "UPLOAD|" + arquivo.getName() + "|" + dados.length + "|" + partes.length + "|" + Protocolo.hash(dados);
            DatagramPacket resposta = Protocolo.enviarEsperar(socketUpload, bytes(pedido), servidor, "ACK|UPLOAD", this);
            verificarErro(resposta);
            int id = Integer.parseInt(Protocolo.texto(resposta).split("\\|")[2]);

            Protocolo.enviarArquivo(socketUpload, resposta.getSocketAddress(), id, partes, this);
        }
    }

    // pede o download, recebe os metadados do arquivo, recebe cada parte, confere hash e salva
    public File download(String nome) throws IOException {
        try (DatagramSocket socketDownload = new DatagramSocket()) {
            DatagramPacket meta = Protocolo.enviarEsperar(socketDownload, bytes("DOWNLOAD|" + nome), servidor, "META", this);
            verificarErro(meta);
            String[] campos = Protocolo.texto(meta).split("\\|");
            int total = Integer.parseInt(campos[4]);

            SocketAddress origem = meta.getSocketAddress();
            byte[] arquivo = Protocolo.receberArquivo(socketDownload, origem, total, "ACK|META", this);
            if (!Protocolo.hash(arquivo).equals(campos[5])) {
                Protocolo.confirmarUltimo(socketDownload, origem, "ERROR|2|hash do arquivo nao confere");
                throw new IOException("hash do arquivo nao confere");
            }
            File destino = new File("downloads", campos[2]);
            destino.getParentFile().mkdirs();
            Files.write(destino.toPath(), arquivo);
            Protocolo.confirmarUltimo(socketDownload, origem, "ACK|" + campos[1] + "|" + (total - 1));
            return destino;
        }
    }

    //verifica c o sv respondeu com erro, caso sim, transforma em exc
    private void verificarErro(DatagramPacket resposta) throws IOException {
        String texto = Protocolo.texto(resposta);
        if (texto.startsWith("ERROR|")) {
            throw new IOException(texto.split("\\|", 3)[2]);
        }
    }

    @Override
    public void aviso(String operacao, String detalhes) {
        tela.mensagem(operacao + ": " + detalhes);
    }

    @Override
    public void progresso(int porcentagem) {
        tela.progresso(porcentagem);
    }

    //helper para n chamar getbytes toda hr
    private byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }
}
